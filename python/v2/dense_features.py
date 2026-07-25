def _get_base_pos(player, rows, cols):
    if player == 1:
        return (0, 0)
    elif player == 2:
        return (rows - 1, cols - 1)
    elif player == 3:
        return (0, cols - 1)
    elif player == 4:
        return (rows - 1, 0)
    return None

def _manhattan_dist(r1, c1, r2, c2):
    return abs(r1 - r2) + abs(c1 - c2)

def extract_dense_features(board, active_player, turn_number, rows=12, cols=12):
    opponent = 3 - active_player

    stm_normal = 0
    nstm_normal = 0
    stm_fortified = 0
    nstm_fortified = 0
    neutral = 0
    empty = 0

    stm_base_alive = 0.0
    nstm_base_alive = 0.0

    stm_cells = []
    nstm_cells = []

    for r in range(rows):
        for c in range(cols):
            cell = board[r][c]
            if cell is None or cell['kind'] == 'EMPTY':
                empty += 1
            elif cell['kind'] == 'NEUTRAL':
                neutral += 1
            elif cell['kind'] == 'NORMAL':
                if cell['owner'] == active_player:
                    stm_normal += 1
                    stm_cells.append((r, c))
                elif cell['owner'] == opponent:
                    nstm_normal += 1
                    nstm_cells.append((r, c))
            elif cell['kind'] == 'FORTIFIED':
                if cell['owner'] == active_player:
                    stm_fortified += 1
                    stm_cells.append((r, c))
                elif cell['owner'] == opponent:
                    nstm_fortified += 1
                    nstm_cells.append((r, c))
            elif cell['kind'] == 'BASE':
                if cell['owner'] == active_player:
                    stm_base_alive = 1.0
                    stm_cells.append((r, c))
                elif cell['owner'] == opponent:
                    nstm_base_alive = 1.0
                    nstm_cells.append((r, c))

    max_dist = float(rows + cols - 2)
    total_area = float(rows * cols)

    # distance to enemy base
    stm_min_dist = max_dist
    nstm_min_dist = max_dist

    nstm_base_pos = _get_base_pos(opponent, rows, cols)
    if nstm_base_pos:
        for r, c in stm_cells:
            d = _manhattan_dist(r, c, nstm_base_pos[0], nstm_base_pos[1])
            if d < stm_min_dist:
                stm_min_dist = d

    stm_base_pos = _get_base_pos(active_player, rows, cols)
    if stm_base_pos:
        for r, c in nstm_cells:
            d = _manhattan_dist(r, c, stm_base_pos[0], stm_base_pos[1])
            if d < nstm_min_dist:
                nstm_min_dist = d

    # connected components
    def get_components(cells, owner):
        if not cells: return 0
        visited = set()
        components = 0
        cell_set = set(cells)
        directions = [(-1,-1), (-1,0), (-1,1), (0,-1), (0,1), (1,-1), (1,0), (1,1)]

        for start_cell in cells:
            if start_cell not in visited:
                components += 1
                queue = [start_cell]
                visited.add(start_cell)
                while queue:
                    cr, cc = queue.pop(0)
                    for dr, dc in directions:
                        nr, nc = cr + dr, cc + dc
                        if (nr, nc) in cell_set and (nr, nc) not in visited:
                            visited.add((nr, nc))
                            queue.append((nr, nc))
        return components

    stm_components = get_components(stm_cells, active_player)
    nstm_components = get_components(nstm_cells, opponent)

    stm_comp_ratio = stm_components / max(1.0, float(len(stm_cells)))
    nstm_comp_ratio = nstm_components / max(1.0, float(len(nstm_cells)))

    features = [
        float(stm_normal) / total_area,
        float(nstm_normal) / total_area,
        float(stm_fortified) / total_area,
        float(nstm_fortified) / total_area,
        float(neutral) / total_area,
        float(empty) / total_area,
        stm_base_alive,
        nstm_base_alive,
        float(stm_min_dist) / max_dist,
        float(nstm_min_dist) / max_dist,
        stm_comp_ratio,
        nstm_comp_ratio,
        float(turn_number) / 100.0,
        total_area / 144.0
    ]
    return features
