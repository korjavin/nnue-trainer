"""Frame-sign and target-building tests for the Phase 2 self-play trainer. Run from repo root:

    .venv/bin/python -m unittest discover -s python/mcts -p "*_test.py"

Skips when torch is absent (same guard as python/v3/train_net_test.py).
"""
import unittest

try:
    import torch  # noqa: F401

    import train_selfplay as ts

    HAVE_TORCH = True
except ImportError:
    HAVE_TORCH = False


def _row(g, mover, z, pi, pv):
    return {
        "g": g,
        "sym": [0] * 144,
        "ml": 3,
        "nuo": 0,
        "nux": 0,
        "mover": mover,
        "pi": pi,
        "pv": pv,
        "z": z,
    }


@unittest.skipUnless(HAVE_TORCH, "torch not installed")
class FrameSignTest(unittest.TestCase):
    def test_mover_z_flips_the_absolute_outcome_into_the_mover_frame(self):
        # z is recorded ABSOLUTE (+1 = P1 won). A P2 row of a P1-won game trains toward -1:
        # the features are mover-relative, so the target must flip with the mover (v3 lesson).
        self.assertEqual(ts.mover_z(1, 1), 1)
        self.assertEqual(ts.mover_z(1, 2), -1)
        self.assertEqual(ts.mover_z(-1, 1), -1)
        self.assertEqual(ts.mover_z(-1, 2), 1)
        self.assertEqual(ts.mover_z(0, 2), 0)

    def test_tensors_build_mover_frame_targets_and_normalized_policy(self):
        rows = [
            _row("a", 1, -1, [3, 7], [1, 3]),  # P2 won, P1 row -> value target -1
            _row("a", 2, -1, [3, 7, 150], [0, 2, 2]),  # same game, P2 row -> +1
        ]
        sym, ml, nuo, nux, idx, w, valid, zt = ts.tensors(rows, "cpu")
        self.assertEqual(zt.tolist(), [-1.0, 1.0])
        self.assertEqual(idx.shape, (2, 3))
        self.assertEqual(w[0].tolist(), [0.25, 0.75, 0.0])
        self.assertEqual(valid[0].tolist(), [True, True, False])
        self.assertEqual(w[1].tolist(), [0.0, 0.5, 0.5])

    def test_mask_keeps_flat_id_zero_legal_despite_padding(self):
        # Row 0 is padded (k=3) and pads carry idx 0 — flat id 0 being genuinely
        # legal must survive the scatter (duplicate-index writes are nondeterministic).
        rows = [
            _row("a", 1, 0, [0, 7], [1, 1]),
            _row("a", 2, 0, [3, 7, 150], [1, 1, 1]),
        ]
        batch = ts.tensors(rows, "cpu")
        model = ts.PolicyValueNet(channels=4, layers=1)
        logits, idx, w, v, zt = ts.forward_batch(model, batch)
        finite0 = (logits[0] > -1e8).nonzero().flatten().tolist()
        self.assertEqual(finite0, [0, 7])
        finite1 = (logits[1] > -1e8).nonzero().flatten().tolist()
        self.assertEqual(finite1, [3, 7, 150])

    def test_mask_covers_exactly_the_legal_set(self):
        rows = [_row("a", 1, 0, [5, 150], [2, 2])]
        batch = ts.tensors(rows, "cpu")
        model = ts.PolicyValueNet(channels=4, layers=1)
        logits, idx, w, v, zt = ts.forward_batch(model, batch)
        finite = (logits[0] > -1e8).nonzero().flatten().tolist()
        self.assertEqual(finite, [5, 150])
        self.assertLessEqual(abs(float(v[0])), 1.0)


if __name__ == "__main__":
    unittest.main()
