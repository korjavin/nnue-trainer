import unittest
import numpy as np
import sys
import os
sys.path.insert(0, os.path.dirname(__file__))
from accumulator import AccumulatorV2

class TestAccumulatorV2(unittest.TestCase):
    def test_forward(self):
        k = 16
        dense_biases = np.zeros(k)
        stm_weights = {0: np.ones(k)}
        nstm_weights = {0: np.ones(k) * 2}

        acc = AccumulatorV2(stm_weights, nstm_weights, dense_biases, k=k)
        board_state = np.zeros((5, 5), dtype=int)
        dense_14 = np.zeros(14)

        # This will just check that it runs and returns right shape
        out = acc.forward(board_state, dense_14)
        self.assertEqual(len(out), k * 2 + 14)

if __name__ == '__main__':
    unittest.main()
