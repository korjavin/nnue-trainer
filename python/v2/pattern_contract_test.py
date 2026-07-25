import unittest
from python.v2.pattern_contract import Board, Cell, CellKind, PatternContract

class TestPatternContract(unittest.TestCase):

    def test_perspective_normalization(self):
        self.assertEqual(PatternContract.EMPTY, PatternContract.get_symbol(Cell(0, CellKind.EMPTY), 1))
        self.assertEqual(PatternContract.NEUTRAL, PatternContract.get_symbol(Cell(0, CellKind.NEUTRAL), 1))

        self.assertEqual(PatternContract.BASE_SELF, PatternContract.get_symbol(Cell(1, CellKind.BASE), 1))
        self.assertEqual(PatternContract.BASE_OPPONENT, PatternContract.get_symbol(Cell(2, CellKind.BASE), 1))

        self.assertEqual(PatternContract.NORMAL_SELF, PatternContract.get_symbol(Cell(1, CellKind.NORMAL), 1))
        self.assertEqual(PatternContract.NORMAL_OPPONENT, PatternContract.get_symbol(Cell(2, CellKind.NORMAL), 1))

        self.assertEqual(PatternContract.FORTIFIED_SELF, PatternContract.get_symbol(Cell(2, CellKind.FORTIFIED), 2))
        self.assertEqual(PatternContract.FORTIFIED_OPPONENT, PatternContract.get_symbol(Cell(1, CellKind.FORTIFIED), 2))

        self.assertEqual(PatternContract.OUT_OF_BOUNDS, PatternContract.get_symbol(None, 1))

    def test_distance_bucket(self):
        board = Board(10, 10)
        board.set_cell(9, 9, Cell(2, CellKind.BASE))

        # Ensure (0,0) window is emitted by putting a piece nearby
        board.set_cell(0, 0, Cell(1, CellKind.NORMAL))

        windows = PatternContract.extract_windows(board, 1)
        w00 = next((w for w in windows if w.center_row == 0 and w.center_col == 0), None)
        self.assertIsNotNone(w00)
        self.assertEqual(7, w00.distance_bucket)

        w88 = next((w for w in windows if w.center_row == 8 and w.center_col == 8), None)
        self.assertIsNotNone(w88)
        self.assertEqual(2, w88.distance_bucket)

    def test_active_window_emission(self):
        board = Board(5, 5)
        board.set_cell(2, 2, Cell(1, CellKind.NORMAL))

        windows = PatternContract.extract_windows(board, 1)
        self.assertEqual(25, len(windows))

        board = Board(5, 5)
        board.set_cell(0, 0, Cell(1, CellKind.NORMAL))
        windows = PatternContract.extract_windows(board, 1)
        self.assertEqual(9, len(windows))

    def test_edge_out_of_bounds_handling(self):
        board = Board(3, 3)
        board.set_cell(0, 0, Cell(1, CellKind.NORMAL))

        windows = PatternContract.extract_windows(board, 1)
        w00 = next((w for w in windows if w.center_row == 0 and w.center_col == 0), None)
        self.assertIsNotNone(w00)

        symbols = w00.symbols
        self.assertEqual(PatternContract.OUT_OF_BOUNDS, symbols[0])
        self.assertEqual(PatternContract.NORMAL_SELF, symbols[12])
        self.assertEqual(PatternContract.EMPTY, symbols[24])
        self.assertEqual(PatternContract.OUT_OF_BOUNDS, symbols[15])

if __name__ == '__main__':
    unittest.main()
