import unittest
import numpy as np
import random
from python.v2.accumulator import NNUEv2Accumulator
from python.v2.incremental_accumulator import NNUEv2IncrementalAccumulator

class TestIncrementalAccumulator(unittest.TestCase):
    def test_parity(self):
        rows, cols = 12, 12
        K = 16
        dense_size = 14

        # Generate a random pattern dict
        pattern_dict = {}
        for i in range(100):
            # Just create 100 random 25-tuples
            pattern = tuple(random.randint(0, 15) for _ in range(25))
            if pattern not in pattern_dict:
                pattern_dict[pattern] = len(pattern_dict)

        # Make a dummy pattern for empty to ensure we have hits
        dummy_pattern = tuple(12 for _ in range(25))
        pattern_dict[dummy_pattern] = len(pattern_dict)

        hidden_weights = np.random.randn(len(pattern_dict), K).astype(np.float32)
        hidden_bias = np.random.randn(K).astype(np.float32)

        full_acc = NNUEv2Accumulator(pattern_dict, hidden_weights, hidden_bias, K, dense_size)
        inc_acc = NNUEv2IncrementalAccumulator(full_acc, rows, cols)

        # Initial board
        board = [[(0, 0) for _ in range(cols)] for _ in range(rows)]

        # Add some initial cells
        for _ in range(20):
            r, c = random.randint(0, rows - 1), random.randint(0, cols - 1)
            board[r][c] = (random.choice([1, 2]), random.choice([0, 1, 2, 3, 4]))

        active_player = 1
        inc_acc.initialize(board, active_player)

        for _ in range(100):
            # Make a random move (change 1 to 3 cells)
            num_changes = random.randint(1, 3)
            modified_cells = []
            for _ in range(num_changes):
                r, c = random.randint(0, rows - 1), random.randint(0, cols - 1)
                board[r][c] = (random.choice([1, 2]), random.choice([0, 1, 2, 3, 4]))
                modified_cells.append((r, c))

            # Sometimes switch players
            if random.random() < 0.3:
                active_player = 3 - active_player

            dense = np.random.randn(dense_size).astype(np.float32)

            res_full = full_acc.compute_full(board, active_player, dense)
            res_inc = inc_acc.update(board, active_player, modified_cells, dense)

            np.testing.assert_allclose(res_full, res_inc, rtol=1e-5, atol=1e-5)

if __name__ == '__main__':
    unittest.main()
