import sqlite3
import json
import os
import argparse

def get_connected_mask(board, player, rows=12, cols=12):
    if player == 1:
        base_row, base_col = 0, 0
    elif player == 2:
        base_row, base_col = rows - 1, cols - 1
    else:
        return set()

    base_cell = board[base_row][base_col]
    if base_cell is None or base_cell['owner'] != player:
        return set()

    connected = set()
    queue = [(base_row, base_col)]
    connected.add((base_row, base_col))

    directions = [(-1,-1), (-1,0), (-1,1), (0,-1), (0,1), (1,-1), (1,0), (1,1)]

    while queue:
        r, c = queue.pop(0)
        for dr, dc in directions:
            nr, nc = r + dr, c + dc
            if 0 <= nr < rows and 0 <= nc < cols:
                if (nr, nc) not in connected:
                    cell = board[nr][nc]
                    if cell is not None and cell['owner'] == player:
                        connected.add((nr, nc))
                        queue.append((nr, nc))
    return connected

def cleanup_disconnected(board, rows=12, cols=12):
    connected_p1 = get_connected_mask(board, 1, rows, cols)
    connected_p2 = get_connected_mask(board, 2, rows, cols)

    for r in range(rows):
        for c in range(cols):
            cell = board[r][c]
            if cell is not None and cell['owner'] in (1, 2) and cell['kind'] not in ('EMPTY', 'NEUTRAL', 'BASE'):
                if cell['owner'] == 1 and (r, c) not in connected_p1:
                    board[r][c] = None
                elif cell['owner'] == 2 and (r, c) not in connected_p2:
                    board[r][c] = None

def map_board_to_features(board, active_player, rows=12, cols=12):
    opponent = 3 - active_player
    features = [0.0] * (rows * cols * 6)

    for r in range(rows):
        for c in range(cols):
            cell = board[r][c]
            state_index = 0  # Default to EMPTY

            if cell is not None:
                kind = cell['kind']
                owner = cell['owner']

                if kind == 'NORMAL':
                    if owner == active_player:
                        state_index = 1
                    elif owner == opponent:
                        state_index = 2
                elif kind == 'FORTIFIED':
                    if owner == active_player:
                        state_index = 3
                    elif owner == opponent:
                        state_index = 4
                elif kind == 'NEUTRAL':
                    state_index = 5

            cell_index = r * cols + c
            features[cell_index * 6 + state_index] = 1.0

    return features

def main():
    parser = argparse.ArgumentParser(description="Import games and generate NNUE dataset.")
    parser.add_argument("--label-mode", choices=["outcome", "td_leaf", "discounted"], default="outcome", help="Label generation mode.")
    parser.add_argument("--lambda-val", type=float, default=0.5, help="Lambda value for td_leaf mode (weight of outcome).")
    parser.add_argument("--allow-missing-scores", action="store_true", help="Allow missing scores (eval defaults to 0).")
    parser.add_argument("--gamma-val", type=float, default=0.98, help="Gamma value for discounted mode (decay factor).")
    args = parser.parse_args()
    if not (0.0 <= args.lambda_val <= 1.0):
        raise ValueError("lambda-val must be in [0, 1]")
    if not (0.0 <= args.gamma_val <= 1.0):
        raise ValueError("gamma-val must be in [0, 1]")

    db_path = os.environ.get("DB_PATH", "/Users/iv/Projects/virusgame/backend/data/games.db")
    if not os.path.exists(db_path):
        print(f"Error: {db_path} not found.")
        return

    conn = sqlite3.connect(db_path)
    cursor = conn.cursor()

    # Only clean GoBot-vs-GoBot games (the diverse epsilon self-play set).
    cursor.execute("""
        SELECT id, result, pgn_content
        FROM games
        WHERE rows = 12 AND cols = 12 AND pgn_content IS NOT NULL AND pgn_content != 'null'
          AND player1_name LIKE 'GoBot%' AND player2_name LIKE 'GoBot%'
    """)
    rows = cursor.fetchall()
    print(f"Found {len(rows)} valid 12x12 games.")

    dataset = []

    for game_id, result, pgn_content in rows:
        # Initialize board
        board = [[None for _ in range(12)] for _ in range(12)]
        board[0][0] = {'owner': 1, 'kind': 'BASE'}
        board[11][11] = {'owner': 2, 'kind': 'BASE'}

        try:
            turns = json.loads(pgn_content)
        except Exception as e:
            print(f"Error decoding PGN for game {game_id}: {e}")
            continue

        total_turns = len(turns)

        for turn_index, turn_data in enumerate(turns):
            player = turn_data['player']
            moves = turn_data.get('moves', [])

            # Extract search_eval from turn data or move objects
            search_eval = 0.0
            found_score = False
            for move in moves:
                if 'score' in move:
                    search_eval = move['score'] / 1000.0
                    found_score = True
                elif 'Score' in move:
                    search_eval = move['Score'] / 1000.0
                    found_score = True

            if args.label_mode == "td_leaf" and not found_score:
                raise ValueError(f"TD_LEAF mode requires 'score' in PGN, but none was found in game {game_id}")

            # Clamp eval
            search_eval = max(-1.0, min(1.0, search_eval))

            for move in moves:
                m_type = move.get('type')
                if m_type in ('place', 'attack'):
                    r = move.get('row', move.get('Row'))
                    c = move.get('col', move.get('Col'))
                    if r is not None and c is not None:
                        board[r][c] = {'owner': player, 'kind': 'NORMAL'}
                elif m_type == 'neutral':
                    for cell in move.get('cells', []):
                        r = cell.get('row', cell.get('Row'))
                        c = cell.get('col', cell.get('Col'))
                        if r is not None and c is not None:
                            board[r][c] = {'owner': 0, 'kind': 'NEUTRAL'}

            # Run cleanup after each turn
            cleanup_disconnected(board)

            # Map the board state to feature vector from active_player's perspective
            features = map_board_to_features(board, player)

            # Outcome base target
            if result == 0:
                outcome = 0.0
            else:
                outcome = 1.0 if result == player else -1.0

            target = outcome
            if args.label_mode == "td_leaf":
                distance = total_turns - turn_index
                current_lambda = args.lambda_val ** distance
                target = (1.0 - current_lambda) * search_eval + current_lambda * outcome
            elif args.label_mode == "discounted":
                distance = total_turns - turn_index
                target = outcome * (args.gamma_val ** distance)

            dataset.append({
                'features': features,
                'target': target,
                'eval': target
            })

    conn.close()

    output_path = os.environ.get("OUTPUT_PATH", "/Users/iv/Projects/nnue-trainer/dataset.json")
    with open(output_path, "w") as f:
        json.dump(dataset, f)

    print(f"Successfully generated dataset with {len(dataset)} position records at {output_path}")

if __name__ == "__main__":
    main()
