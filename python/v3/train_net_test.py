"""Tests for the NNUE v3 ordering net. Run from repo root:

    python3 -m unittest discover -s python/v3 -p "*_test.py"

The committed nnue_v3_net.json is checked with numpy only, because that artifact is what the Java
runtime parses and CI does not install torch. The training-path tests skip when torch is absent.
"""
import json
import math
import os
import tempfile
import unittest

import numpy as np

try:
    from python.v3.train_net import (
        N_ACTIVE,
        N_FEATURES,
        Net,
        export,
        groups,
        metrics,
        rankdata,
        spearman,
        split_games,
    )

    import torch

    HAS_TORCH = True
except ImportError:  # CI installs numpy only
    HAS_TORCH = False
    N_FEATURES, N_ACTIVE = 1152, 144

COMMITTED_NET = os.path.join(os.path.dirname(__file__), "..", "..", "nnue_v3_net.json")


class CommittedNetTest(unittest.TestCase):
    """The shipped artifact's schema, pinned because a Java runtime parses it."""

    def setUp(self):
        if not os.path.exists(COMMITTED_NET):
            self.skipTest("nnue_v3_net.json not present")
        with open(COMMITTED_NET) as f:
            self.doc = json.load(f)

    def test_shapes_match_meta(self):
        meta, doc = self.doc["meta"], self.doc
        h = meta["hidden"]
        self.assertEqual(meta["features"], N_FEATURES)
        self.assertEqual(meta["arch"], "1152-%d-1" % h)
        self.assertEqual(meta["activation"], "relu")
        self.assertEqual(meta["score_units"], "hand_tuned")
        self.assertEqual(len(doc["w1"]), h)
        self.assertTrue(all(len(row) == N_FEATURES for row in doc["w1"]))
        self.assertEqual(len(doc["b1"]), h)
        self.assertEqual(len(doc["w2"]), h)
        self.assertIsInstance(doc["b2"], float)

    def test_all_finite(self):
        # json.load happily parses NaN/Infinity tokens; Jackson rejects them outright, so a net
        # with a blown-up weight would ship and only fail inside the engine.
        flat = [v for row in self.doc["w1"] for v in row]
        flat += self.doc["b1"] + self.doc["w2"] + [self.doc["b2"]]
        self.assertTrue(all(math.isfinite(v) for v in flat))

    def test_meta_records_a_holdout_measurement(self):
        meta = self.doc["meta"]
        self.assertGreater(meta["games_holdout"], 0)
        self.assertGreater(meta["games_train"], meta["games_holdout"])
        self.assertTrue(0.0 <= meta["top1_holdout"] <= 1.0)
        for k in ("spearman_holdout", "mae_holdout", "r2_holdout"):
            self.assertTrue(math.isfinite(meta[k]))


class RankingMathTest(unittest.TestCase):
    @unittest.skipUnless(HAS_TORCH, "torch not installed")
    def test_rankdata_averages_ties(self):
        np.testing.assert_allclose(rankdata(np.array([10.0, 20.0, 20.0, 5.0])), [2, 3.5, 3.5, 1])

    @unittest.skipUnless(HAS_TORCH, "torch not installed")
    def test_spearman_is_monotone_invariant(self):
        a = np.array([1.0, 2.0, 3.0, 4.0])
        self.assertAlmostEqual(spearman(a, np.exp(a)), 1.0)
        self.assertAlmostEqual(spearman(a, -a), -1.0)

    @unittest.skipUnless(HAS_TORCH, "torch not installed")
    def test_metrics_on_a_perfect_and_a_reversed_ranker(self):
        pos = np.array([0, 0, 0, 1, 1, 1])
        start, length = groups(pos)
        ht = np.array([3.0, 1.0, 2.0, 5.0, 9.0, 7.0])
        gids = np.array([0, 1])
        self.assertEqual(metrics(ht, ht, start, length, gids)["top1"], 1.0)
        rev = metrics(-ht, ht, start, length, gids)
        self.assertEqual(rev["top1"], 0.0)
        self.assertAlmostEqual(rev["spearman_mean"], -1.0)

    @unittest.skipUnless(HAS_TORCH, "torch not installed")
    def test_split_is_by_whole_game(self):
        gidx = np.repeat(np.arange(10), 5)
        parts = split_games(gidx, seed=0)
        for a, b in ((0, 1), (0, 2), (1, 2)):
            self.assertFalse(set(gidx[parts[a]]) & set(gidx[parts[b]]))
        self.assertEqual(sum(int(p.sum()) for p in parts), len(gidx))


class ExportTest(unittest.TestCase):
    @unittest.skipUnless(HAS_TORCH, "torch not installed")
    def test_exported_weights_reproduce_the_model_in_hand_tuned_units(self):
        """The file's own arithmetic must equal the torch model's, standardisation folded in."""
        torch.manual_seed(0)
        model = Net(hidden=4)
        mu, sigma = -4255.0, 20000.0
        idx = np.random.default_rng(0).integers(0, N_FEATURES, size=(3, N_ACTIVE))
        model.eval()
        with torch.no_grad():
            want = model(torch.from_numpy(idx)).numpy() * sigma + mu

        with tempfile.TemporaryDirectory() as d:
            doc = export(model, mu, sigma, {"hidden": 4}, os.path.join(d, "net.json"))
        w1 = np.array(doc["w1"])
        got = np.array(
            [
                np.maximum(w1[:, row].sum(axis=1) + np.array(doc["b1"]), 0.0)
                @ np.array(doc["w2"])
                + doc["b2"]
                for row in idx
            ]
        )
        np.testing.assert_allclose(got, want, rtol=1e-5, atol=1e-3)


if __name__ == "__main__":
    unittest.main()
