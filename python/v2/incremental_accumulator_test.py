import unittest
import numpy as np
import random
import copy
from python.v2.accumulator import NNUEv2Accumulator
from python.v2.incremental_accumulator import update_accumulator

class TestIncrementalAccumulator(unittest.TestCase):
    def test_incremental_parity(self):
        random.seed(42)
        np.random.seed(42)

        K = 16
        dense_size = 14
        rows, cols = 8, 8

        # We don't have all patterns in dict, so let's just make a very permissive one
        pattern_dict = {}
        # We will assign patterns IDs on the fly in a dict that acts like defaultdict
        class AutoDict(dict):
            def __init__(self, *args, **kwargs):
                super().__init__(*args, **kwargs)
                self.next_id = 0
            def __missing__(self, key):
                self[key] = self.next_id
                self.next_id += 1
                return self[key]

        pattern_dict = AutoDict()

        # Max expected unique patterns around 1000 for this small test
        hidden_weights = np.random.randn(2000, K).astype(np.float32)
        hidden_bias = np.random.randn(K).astype(np.float32)

        acc = NNUEv2Accumulator(pattern_dict, hidden_weights, hidden_bias, K, dense_size)

        board = [[(0, 0) for _ in range(cols)] for _ in range(rows)]
        board[0][0] = (1, 2) # P1 BASE
        board[7][7] = (2, 2) # P2 BASE

        active_player = 1

        # initial full compute
        # Wait, full compute doesn't mutate pattern_dict if it uses __contains__ in accumulator.py
        # Actually accumulator.py uses `if pattern in self.pattern_dict:`.
        # So we need to populate pattern_dict or override extract to populate.

        def mock_extract_and_add(board_cells, active_player):
            for r in range(rows):
                for c in range(cols):
                    cell = board_cells[r][c]
                    if cell is None: continue
                    o, k = cell
                    if k == 0 or k == 2: continue
                    p1 = acc.extract_pattern(board_cells, r, c, 1)
                    p2 = acc.extract_pattern(board_cells, r, c, 2)
                    _ = pattern_dict[p1]
                    _ = pattern_dict[p2]

        # 100 random moves

        for _ in range(100):
            old_board = copy.deepcopy(board)

            # do a random modification (1 or 2 cells)
            num_mods = random.randint(1, 2)
            mods = []
            for _ in range(num_mods):
                r = random.randint(0, rows-1)
                c = random.randint(0, cols-1)
                if board[r][c][1] == 2: # don't overwrite base
                    continue
                o = random.randint(1, 2)
                k = random.randint(0, 4)
                board[r][c] = (o, k)
                mods.append((r, c))

            active_player = 3 - active_player

            # populate dict
            mock_extract_and_add(old_board, active_player)
            mock_extract_and_add(board, active_player)

            # full compute on old board
            full_old = acc.compute_full(old_board, active_player)
            accum_stm_old = full_old[:K].copy()
            accum_nstm_old = full_old[K:2*K].copy()

            # full compute on new board
            full_new = acc.compute_full(board, active_player)
            expected_stm = full_new[:K]
            expected_nstm = full_new[K:2*K]

            # incremental update
            inc_stm, inc_nstm = update_accumulator(acc, old_board, board, mods, accum_stm_old, accum_nstm_old, active_player)

            np.testing.assert_allclose(inc_stm, expected_stm, rtol=1e-5, atol=1e-5)
            np.testing.assert_allclose(inc_nstm, expected_nstm, rtol=1e-5, atol=1e-5)

if __name__ == '__main__':
    unittest.main()
