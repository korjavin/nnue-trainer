class Symbol:
    EMPTY = 0
    NEUTRAL = 1
    BASE_SELF = 2
    BASE_OPPONENT = 3
    NORMAL_SELF = 4
    NORMAL_OPPONENT = 5
    FORTIFIED_SELF = 6
    FORTIFIED_OPPONENT = 7
    OUT_OF_BOUNDS = 8

class CellKind:
    EMPTY = 0
    NORMAL = 1
    BASE = 2
    FORTIFIED = 3
    NEUTRAL = 4

def normalize_manhattan(r, c, enemy_base_r, enemy_base_c, rows, cols):
    dist = abs(r - enemy_base_r) + abs(c - enemy_base_c)
    max_dist = (rows - 1) + (cols - 1)
    if max_dist == 0:
        return 0
    return min(7, int((dist * 7) / max_dist))

def extract_windows(board_cells, side_to_move, enemy_base_r, enemy_base_c, rows, cols):
    """
    board_cells is a list of lists of (owner, kind)
    """
    windows = []

    for r in range(rows):
        for c in range(cols):
            center_owner, center_kind = board_cells[r][c]
            # Active-Window Emission Rule:
            # Only emit 5x5 windows centered at cells that contain non-empty / non-neutral pieces or active board cells.
            if center_kind == CellKind.EMPTY or center_kind == CellKind.NEUTRAL:
                continue

            window = []

            for wr in range(r - 2, r + 3):
                for wc in range(c - 2, c + 3):
                    if wr < 0 or wr >= rows or wc < 0 or wc >= cols:
                        window.append(Symbol.OUT_OF_BOUNDS)
                    else:
                        owner, kind = board_cells[wr][wc]

                        if kind == CellKind.EMPTY:
                            window.append(Symbol.EMPTY)
                        elif kind == CellKind.NEUTRAL:
                            window.append(Symbol.NEUTRAL)
                        else:
                            is_self = (owner == side_to_move)
                            if kind == CellKind.BASE:
                                window.append(Symbol.BASE_SELF if is_self else Symbol.BASE_OPPONENT)
                            elif kind == CellKind.NORMAL:
                                window.append(Symbol.NORMAL_SELF if is_self else Symbol.NORMAL_OPPONENT)
                            elif kind == CellKind.FORTIFIED:
                                window.append(Symbol.FORTIFIED_SELF if is_self else Symbol.FORTIFIED_OPPONENT)
                            else:
                                window.append(Symbol.EMPTY)

            dist_bucket = normalize_manhattan(r, c, enemy_base_r, enemy_base_c, rows, cols)
            windows.append({
                "center": (r, c),
                "pattern": window,
                "distance_bucket": dist_bucket
            })

    return windows
