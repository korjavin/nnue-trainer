import json
import sys
import collections
from typing import Dict, List, Tuple
from python.v2.pattern_contract import Board, Cell, CellKind, PatternContract

def signature_to_string(symbols: List[int], distance_bucket: int) -> str:
    return ",".join(map(str, symbols)) + ":" + str(distance_bucket)

def convert_dict_board_to_board(dict_board: List[List[Dict]], rows: int = 12, cols: int = 12) -> Board:
    board = Board(rows, cols)
    for r in range(rows):
        for c in range(cols):
            cell_data = dict_board[r][c]
            if cell_data is None:
                board.set_cell(r, c, Cell(0, CellKind.EMPTY))
                continue

            owner = cell_data.get('owner', 0)
            kind_str = cell_data.get('kind', 'EMPTY')

            if kind_str == 'EMPTY':
                kind = CellKind.EMPTY
            elif kind_str == 'NORMAL':
                kind = CellKind.NORMAL
            elif kind_str == 'BASE':
                kind = CellKind.BASE
            elif kind_str == 'FORTIFIED':
                kind = CellKind.FORTIFIED
            elif kind_str == 'NEUTRAL':
                kind = CellKind.NEUTRAL
            else:
                kind = CellKind.EMPTY

            board.set_cell(r, c, Cell(owner, kind))
    return board

def mine_patterns(positions: List[Dict], min_count: int = 5) -> Dict:
    """
    positions: list of dicts with 'board' (12x12 grid) and 'player' (1 or 2)
    """
    frequencies = collections.Counter()

    for pos in positions:
        board_dict = pos['board']
        stm_owner = pos['player']
        board = convert_dict_board_to_board(board_dict)
        windows = PatternContract.extract_windows(board, stm_owner)

        for w in windows:
            sig = signature_to_string(w.symbols, w.distance_bucket)
            frequencies[sig] += 1

    promoted = {sig: count for sig, count in frequencies.items() if count >= min_count}

    # Sort deterministically: by descending frequency, then alphabetically by signature
    sorted_patterns = sorted(promoted.items(), key=lambda x: (-x[1], x[0]))

    pattern_to_id = {}
    for idx, (sig, _) in enumerate(sorted_patterns):
        pattern_to_id[sig] = idx

    return {
        "pattern_to_id": pattern_to_id,
        "metadata": {
            "num_patterns": len(pattern_to_id),
            "min_count": min_count,
            "version": 2
        }
    }

def main():
    if len(sys.argv) < 3:
        print("Usage: python3 mine_patterns.py <input.jsonl> <output.json>")
        sys.exit(1)

    input_path = sys.argv[1]
    output_path = sys.argv[2]
    min_count = 5
    if len(sys.argv) >= 4:
        min_count = int(sys.argv[3])

    positions = []
    with open(input_path, 'r') as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            pos = json.loads(line)
            positions.append(pos)

    dictionary = mine_patterns(positions, min_count)

    with open(output_path, 'w') as f:
        json.dump(dictionary, f, indent=2)

    print(f"Mined {dictionary['metadata']['num_patterns']} patterns (min_count={min_count}) to {output_path}")

if __name__ == '__main__':
    main()
