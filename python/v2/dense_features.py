def get_manhattan_dist(r1, c1, r2, c2):
    return abs(r1 - r2) + abs(c1 - c2)

def extract_dense_features(board, stm, turn_number):
    """
    Extracts the 14 dense manual features from the board state.

    board: 2D list of dicts or None, e.g. [[{'owner': 1, 'kind': 'NORMAL'}, ...], ...]
    stm: 1 or 2
    turn_number: int

    Returns a list of 14 floats.
    """
    rows = len(board)
    cols = len(board[0]) if rows > 0 else 0
    area = rows * cols

    nstm = 3 - stm

    stm_normal = 0
    nstm_normal = 0
    stm_fort = 0
    nstm_fort = 0
    neutral = 0
    empty = 0

    stm_base_pos = None
    nstm_base_pos = None
    stm_base_alive = 0.0
    nstm_base_alive = 0.0

    stm_pieces = []
    nstm_pieces = []

    for r in range(rows):
        for c in range(cols):
            cell = board[r][c]
            if cell is None or cell.get('kind') == 'EMPTY':
                empty += 1
            elif cell.get('kind') == 'NEUTRAL':
                neutral += 1
            elif cell.get('kind') == 'BASE':
                if cell.get('owner') == stm:
                    stm_base_alive = 1.0
                    stm_base_pos = (r, c)
                    stm_pieces.append((r, c))
                elif cell.get('owner') == nstm:
                    nstm_base_alive = 1.0
                    nstm_base_pos = (r, c)
                    nstm_pieces.append((r, c))
            elif cell.get('kind') == 'NORMAL':
                if cell.get('owner') == stm:
                    stm_normal += 1
                    stm_pieces.append((r, c))
                elif cell.get('owner') == nstm:
                    nstm_normal += 1
                    nstm_pieces.append((r, c))
            elif cell.get('kind') == 'FORTIFIED':
                if cell.get('owner') == stm:
                    stm_fort += 1
                    stm_pieces.append((r, c))
                elif cell.get('owner') == nstm:
                    nstm_fort += 1
                    nstm_pieces.append((r, c))

    max_dist = rows + cols - 2 if rows > 0 and cols > 0 else 1

    stm_min_dist = 1.0
    if nstm_base_pos and stm_pieces:
        dists = [get_manhattan_dist(r, c, nstm_base_pos[0], nstm_base_pos[1]) for r, c in stm_pieces]
        stm_min_dist = min(dists) / max_dist

    nstm_min_dist = 1.0
    if stm_base_pos and nstm_pieces:
        dists = [get_manhattan_dist(r, c, stm_base_pos[0], stm_base_pos[1]) for r, c in nstm_pieces]
        nstm_min_dist = min(dists) / max_dist

    def get_cc_ratio(pieces):
        if not pieces:
            return 0.0

        visited = set()
        num_ccs = 0
        piece_set = set(pieces)

        for r, c in pieces:
            if (r, c) not in visited:
                num_ccs += 1
                queue = [(r, c)]
                visited.add((r, c))

                while queue:
                    curr_r, curr_c = queue.pop(0)
                    # 8-way connection
                    for dr in [-1, 0, 1]:
                        for dc in [-1, 0, 1]:
                            if dr == 0 and dc == 0:
                                continue
                            nr, nc = curr_r + dr, curr_c + dc
                            if (nr, nc) in piece_set and (nr, nc) not in visited:
                                visited.add((nr, nc))
                                queue.append((nr, nc))

        return 1.0 / num_ccs if num_ccs > 0 else 0.0

    stm_cc_ratio = get_cc_ratio(stm_pieces)
    nstm_cc_ratio = get_cc_ratio(nstm_pieces)

    turn_number_norm = turn_number / 100.0

    board_size_norm = area / 144.0

    return [
        stm_normal / area if area > 0 else 0.0,
        nstm_normal / area if area > 0 else 0.0,
        stm_fort / area if area > 0 else 0.0,
        nstm_fort / area if area > 0 else 0.0,
        neutral / area if area > 0 else 0.0,
        empty / area if area > 0 else 0.0,
        stm_base_alive,
        nstm_base_alive,
        stm_min_dist,
        nstm_min_dist,
        stm_cc_ratio,
        nstm_cc_ratio,
        turn_number_norm,
        board_size_norm
    ]
