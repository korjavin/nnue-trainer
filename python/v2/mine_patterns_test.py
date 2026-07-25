import unittest
from python.v2.mine_patterns import convert_dict_board_to_board, mine_patterns, signature_to_string
from python.v2.pattern_contract import CellKind

class TestMinePatterns(unittest.TestCase):

    def test_mine_patterns(self):
        # Create a simple 12x12 board representation
        board = [[None for _ in range(12)] for _ in range(12)]

        # Add some cells
        board[0][0] = {'owner': 1, 'kind': 'BASE'}
        board[1][1] = {'owner': 1, 'kind': 'NORMAL'}

        board[11][11] = {'owner': 2, 'kind': 'BASE'}

        positions = [
            {'board': board, 'player': 1}
        ] * 10  # 10 identical positions

        # Mine with min_count = 5
        dictionary = mine_patterns(positions, min_count=5)

        self.assertIn('pattern_to_id', dictionary)
        self.assertIn('metadata', dictionary)

        metadata = dictionary['metadata']
        self.assertEqual(5, metadata['min_count'])
        self.assertEqual(2, metadata['version'])
        self.assertGreater(metadata['num_patterns'], 0)

        # Assert determinism
        dict2 = mine_patterns(positions, min_count=5)
        self.assertEqual(dictionary['pattern_to_id'], dict2['pattern_to_id'])

        # Ensure signatures format is correct
        sample_key = list(dictionary['pattern_to_id'].keys())[0]
        self.assertTrue(':' in sample_key)
        symbols_str, dist_str = sample_key.split(':')
        self.assertEqual(25, len(symbols_str.split(',')))
        self.assertTrue(dist_str.isdigit())

    def test_convert_dict_board_to_board(self):
        board_dict = [[None for _ in range(12)] for _ in range(12)]
        board_dict[5][5] = {'owner': 1, 'kind': 'FORTIFIED'}

        board = convert_dict_board_to_board(board_dict)
        cell = board.get_cell(5, 5)

        self.assertIsNotNone(cell)
        self.assertEqual(1, cell.owner)
        self.assertEqual(CellKind.FORTIFIED, cell.kind)

        empty_cell = board.get_cell(0, 0)
        self.assertEqual(CellKind.EMPTY, empty_cell.kind)

if __name__ == '__main__':
    unittest.main()
