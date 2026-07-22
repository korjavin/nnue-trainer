import unittest
from pattern_contract import extract_windows, normalize_manhattan, Symbol, CellKind

class TestPatternContract(unittest.TestCase):
    def test_normalize_manhattan(self):
        # 12x12 board max distance is 22
        # r=0, c=0 to r=11, c=11 is 22
        self.assertEqual(normalize_manhattan(0, 0, 11, 11, 12, 12), 7)
        self.assertEqual(normalize_manhattan(11, 11, 11, 11, 12, 12), 0)
        self.assertEqual(normalize_manhattan(5, 5, 11, 11, 12, 12), 3) # 12 distance: 12*7/22 = 3.81 -> int = 3

    def test_extract_windows(self):
        rows, cols = 5, 5
        board = [[(0, CellKind.EMPTY) for _ in range(cols)] for _ in range(rows)]

        # Center has normal piece for player 1
        board[2][2] = (1, CellKind.NORMAL)

        windows = extract_windows(board, 1, 4, 4, rows, cols)

        self.assertEqual(len(windows), 1) # Only one window has a non-empty piece

        w = windows[0]
        self.assertEqual(w["center"], (2, 2))

        # Check window bounds and contents
        # Window around 2,2 covers 0,0 to 4,4
        pattern = w["pattern"]
        self.assertEqual(len(pattern), 25)

        # Center of 5x5 window is at index 12
        self.assertEqual(pattern[12], Symbol.NORMAL_SELF)
        self.assertEqual(pattern[0], Symbol.EMPTY)

    def test_extract_windows_oob(self):
        rows, cols = 5, 5
        board = [[(0, CellKind.EMPTY) for _ in range(cols)] for _ in range(rows)]

        # Top left has piece for player 2
        board[0][0] = (2, CellKind.FORTIFIED)

        windows = extract_windows(board, 1, 4, 4, rows, cols) # Player 1 to move

        self.assertEqual(len(windows), 1)
        w = windows[0]
        self.assertEqual(w["center"], (0, 0))

        pattern = w["pattern"]
        # Top-left of window (w_r = -2, w_c = -2) should be OOB
        self.assertEqual(pattern[0], Symbol.OUT_OF_BOUNDS)

        # Center of window (which maps to 0,0 on board) should be FORTIFIED_OPPONENT
        self.assertEqual(pattern[12], Symbol.FORTIFIED_OPPONENT)

    def test_skip_empty_windows(self):
        rows, cols = 5, 5
        board = [[(0, CellKind.EMPTY) for _ in range(cols)] for _ in range(rows)]

        windows = extract_windows(board, 1, 4, 4, rows, cols)

        self.assertEqual(len(windows), 0)

if __name__ == '__main__':
    unittest.main()
