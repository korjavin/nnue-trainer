"""Board-size independence proof runs finite, deterministic, shape-correct
evals on non-12x12 boards with a tiny untrained model (fast — no real train)."""
import os
import sys
import unittest

import torch

_HERE = os.path.dirname(os.path.abspath(__file__))
_REPO_ROOT = os.path.abspath(os.path.join(_HERE, "..", ".."))
if _REPO_ROOT not in sys.path:
    sys.path.insert(0, _REPO_ROOT)

from python.v2 import train_v2, validate_v2


def _tiny_model():
    train_v2.set_seed(0)  # deterministic init
    return train_v2.NNUEv2(num_patterns=64, W=4)


# Dictionary the tiny model was NOT built from; ids just need to be < num_patterns.
_PATTERN_TO_ID = {"sig-a": 1, "sig-b": 2, "sig-c": 3}


class BoardSizeProofTest(unittest.TestCase):
    def test_non_12x12_sizes_finite_and_scalar(self):
        model = _tiny_model()
        rows = validate_v2.board_size_proof(model, _PATTERN_TO_ID, sizes=[(5, 5), (7, 9)])
        self.assertEqual([(r["rows"], r["cols"]) for r in rows], [(5, 5), (7, 9)])
        for r in rows:
            self.assertTrue(r["finite"])
            self.assertIsInstance(r["eval"], float)

    def test_deterministic_across_runs(self):
        e1 = validate_v2.board_size_proof(_tiny_model(), _PATTERN_TO_ID, sizes=[(5, 7)])
        e2 = validate_v2.board_size_proof(_tiny_model(), _PATTERN_TO_ID, sizes=[(5, 7)])
        self.assertEqual(e1[0]["eval"], e2[0]["eval"])

    def test_synth_board_shape_matches_request(self):
        b = validate_v2.synth_board(6, 8)
        self.assertEqual((b.rows, b.cols), (6, 8))


if __name__ == "__main__":
    unittest.main()
