import enum
from typing import List, Optional, Tuple

class CellKind(enum.Enum):
    EMPTY = 0
    NORMAL = 1
    BASE = 2
    FORTIFIED = 3
    NEUTRAL = 4

class Cell:
    def __init__(self, owner: int, kind: CellKind):
        self.owner = owner
        self.kind = kind

class Board:
    def __init__(self, rows: int, cols: int):
        self.rows = rows
        self.cols = cols
        self.cells = [[Cell(0, CellKind.EMPTY) for _ in range(cols)] for _ in range(rows)]

    def set_cell(self, r: int, c: int, cell: Cell):
        if self.is_valid_pos(r, c):
            self.cells[r][c] = cell

    def get_cell(self, r: int, c: int) -> Optional[Cell]:
        if self.is_valid_pos(r, c):
            return self.cells[r][c]
        return None

    def is_valid_pos(self, r: int, c: int) -> bool:
        return 0 <= r < self.rows and 0 <= c < self.cols

class PatternContract:
    EMPTY = 0
    NEUTRAL = 1
    BASE_SELF = 2
    BASE_OPPONENT = 3
    NORMAL_SELF = 4
    NORMAL_OPPONENT = 5
    FORTIFIED_SELF = 6
    FORTIFIED_OPPONENT = 7
    OUT_OF_BOUNDS = 8

    class Window:
        def __init__(self, center_row: int, center_col: int, symbols: List[int], distance_bucket: int):
            self.center_row = center_row
            self.center_col = center_col
            self.symbols = symbols
            self.distance_bucket = distance_bucket

    @staticmethod
    def get_symbol(cell: Optional[Cell], stm_owner: int) -> int:
        if cell is None:
            return PatternContract.OUT_OF_BOUNDS
        if cell.kind == CellKind.EMPTY:
            return PatternContract.EMPTY
        if cell.kind == CellKind.NEUTRAL:
            return PatternContract.NEUTRAL

        is_self = (cell.owner == stm_owner)

        if cell.kind == CellKind.BASE:
            return PatternContract.BASE_SELF if is_self else PatternContract.BASE_OPPONENT
        elif cell.kind == CellKind.NORMAL:
            return PatternContract.NORMAL_SELF if is_self else PatternContract.NORMAL_OPPONENT
        elif cell.kind == CellKind.FORTIFIED:
            return PatternContract.FORTIFIED_SELF if is_self else PatternContract.FORTIFIED_OPPONENT

        return PatternContract.EMPTY

    @staticmethod
    def get_distance_bucket(r: int, c: int, enemy_base: Optional[Tuple[int, int]]) -> int:
        if enemy_base is None:
            return 7
        dist = abs(r - enemy_base[0]) + abs(c - enemy_base[1])
        return min(dist, 7)

    @staticmethod
    def find_enemy_base(board: Board, stm_owner: int) -> Optional[Tuple[int, int]]:
        for r in range(board.rows):
            for c in range(board.cols):
                cell = board.get_cell(r, c)
                if cell is not None and cell.kind == CellKind.BASE and cell.owner != stm_owner:
                    return (r, c)
        return None

    @staticmethod
    def extract_windows(board: Board, stm_owner: int) -> List['PatternContract.Window']:
        windows = []
        enemy_base = PatternContract.find_enemy_base(board, stm_owner)

        for r in range(board.rows):
            for c in range(board.cols):
                symbols = [0] * 25
                all_empty_or_oob = True
                idx = 0

                for wr in range(r - 2, r + 3):
                    for wc in range(c - 2, c + 3):
                        if not board.is_valid_pos(wr, wc):
                            sym = PatternContract.OUT_OF_BOUNDS
                        else:
                            cell = board.get_cell(wr, wc)
                            sym = PatternContract.get_symbol(cell, stm_owner)

                        symbols[idx] = sym
                        idx += 1

                        if sym != PatternContract.EMPTY and sym != PatternContract.OUT_OF_BOUNDS:
                            all_empty_or_oob = False

                if not all_empty_or_oob:
                    windows.append(PatternContract.Window(
                        r, c, symbols, PatternContract.get_distance_bucket(r, c, enemy_base)
                    ))

        return windows
