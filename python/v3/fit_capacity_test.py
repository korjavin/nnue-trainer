"""Tests for the NNUE v3 capacity fitter. Run from repo root:

    python3 -m unittest discover -s python/v3 -p "*_test.py"
"""
import json
import os
import tempfile
import unittest

import numpy as np

from python.v3.fit_capacity import (
    N_FEATURES,
    design,
    evaluate,
    load_positions,
    main,
    r2,
    ranked_features,
    ridge,
    split_by_game,
    weights_json,
)

COMMITTED_WEIGHTS = os.path.join(
    os.path.dirname(__file__), "..", "..", "nnue_v3_weights.json"
)


def synthetic(n_cells=6, n_states=4, n_games=10, per_game=8, seed=1):
    """Positions with the real structure: exactly one state active per cell."""
    rng = np.random.default_rng(seed)
    n_features = n_cells * n_states
    truth = rng.normal(0, 100, n_features)
    bias = 250.0
    game_ids, active = [], []
    for g in range(n_games):
        for _ in range(per_game):
            states = rng.integers(0, n_states, n_cells)
            active.append([c * n_states + s for c, s in enumerate(states)])
            game_ids.append(g)
    active = np.array(active, dtype=np.int32)
    y = truth[active].sum(axis=1) + bias
    return np.array(game_ids), y, active, truth, bias, n_features


class RidgeArithmeticTest(unittest.TestCase):
    def test_known_answer_on_independent_columns(self):
        # Non-degenerate design, so the weights themselves are identifiable.
        X = np.array([[1.0, 0.0, 1.0], [0.0, 1.0, 1.0], [1.0, 1.0, 1.0], [2.0, 0.0, 1.0]])
        y = X @ np.array([3.0, -5.0, 7.0])
        w, bias = ridge(X, y, lam=0.0)
        np.testing.assert_allclose(w, [3.0, -5.0], atol=1e-8)
        self.assertAlmostEqual(bias, 7.0, places=8)

    def test_bias_is_not_shrunk(self):
        X = np.hstack([np.zeros((4, 1)), np.ones((4, 1))])
        y = np.full(4, 42.0)
        _, bias = ridge(X, y, lam=1e6)
        self.assertAlmostEqual(bias, 42.0, places=6)


class SyntheticRecoveryTest(unittest.TestCase):
    def test_recovers_a_known_linear_eval(self):
        game_ids, y, active, _, _, n_features = synthetic()
        ids = np.arange(n_features, dtype=np.int32)
        X = design(active, ids, n_features=n_features)
        w, bias = ridge(X, y, lam=1e-6)
        pred = X[:, :-1] @ w + bias
        # Weights are only identifiable up to a per-cell constant (one state is
        # always active, so each cell's columns sum to the intercept). The
        # PREDICTION is what has to come back exactly.
        np.testing.assert_allclose(pred, y, atol=1e-4)
        self.assertGreater(r2(y, pred), 0.9999)

    def test_holdout_r2_on_a_perfectly_linear_target(self):
        game_ids, y, active, _, _, n_features = synthetic(n_games=30)
        train, holdout = split_by_game(game_ids, 0.2, seed=3)
        ids = np.arange(n_features, dtype=np.int32)
        m = evaluate(active, y, ids, train, holdout, lam=1e-6)
        self.assertGreater(m["r2_holdout"], 0.999)
        self.assertLess(m["mae_holdout"], 1.0)

    def test_lambda_zero_stays_finite_on_the_rank_deficient_design(self):
        game_ids, y, active, _, _, n_features = synthetic()
        train, holdout = split_by_game(game_ids, 0.2, seed=0)
        m = evaluate(active, y, np.arange(n_features, dtype=np.int32), train, holdout, lam=0.0)
        self.assertTrue(np.all(np.isfinite(m["weights"])))
        self.assertTrue(np.isfinite(m["r2_holdout"]))


class SplitByGameTest(unittest.TestCase):
    def test_no_game_lands_on_both_sides(self):
        game_ids = np.repeat(np.arange(20), 7)
        train, holdout = split_by_game(game_ids, 0.25, seed=5)
        self.assertTrue(np.all(train ^ holdout))  # every position on exactly one side
        self.assertFalse(set(game_ids[train].tolist()) & set(game_ids[holdout].tolist()))
        self.assertEqual(len(np.unique(game_ids[holdout])), 5)

    def test_deterministic_for_a_seed(self):
        game_ids = np.repeat(np.arange(13), 3)
        a, _ = split_by_game(game_ids, 0.3, seed=7)
        b, _ = split_by_game(game_ids, 0.3, seed=7)
        np.testing.assert_array_equal(a, b)
        c, _ = split_by_game(game_ids, 0.3, seed=8)
        self.assertFalse(np.array_equal(a, c))

    def test_uuid_string_game_ids(self):
        # The real games.db keys games by TEXT uuid, so the split must group on
        # strings without any numeric coercion.
        ids = np.array(["00e6-a", "0129-b", "abcd-c", "ef01-d"]).repeat(4)
        train, holdout = split_by_game(ids, 0.25, seed=2)
        self.assertEqual(len(np.unique(ids[holdout])), 1)
        self.assertFalse(set(ids[train].tolist()) & set(ids[holdout].tolist()))

    def test_single_game_corpus_keeps_everything_in_train(self):
        train, holdout = split_by_game(np.zeros(5, dtype=np.int64), 0.2, seed=0)
        self.assertTrue(np.all(train))
        self.assertFalse(np.any(holdout))


class DesignTest(unittest.TestCase):
    def test_selected_subset_drops_unselected_features_only(self):
        active = np.array([[0, 9], [1, 8]], dtype=np.int32)
        X = design(active, np.array([0, 1], dtype=np.int32), n_features=16)
        np.testing.assert_array_equal(X, [[1, 0, 1], [0, 1, 1]])  # trailing intercept

    def test_row_sums_match_active_count_when_all_features_selected(self):
        _, _, active, _, _, n_features = synthetic()
        X = design(active, np.arange(n_features, dtype=np.int32), n_features=n_features)
        np.testing.assert_array_equal(X[:, :-1].sum(axis=1), active.shape[1])


class RankedFeaturesTest(unittest.TestCase):
    def _stats(self, d):
        path = os.path.join(d, "stats.json")
        with open(path, "w") as f:
            json.dump(
                {
                    "features": [
                        {"row": 0, "col": 0, "state": 1, "rank": 1},
                        {"row": 1, "col": 2, "state": 3, "rank": 0},
                        {"row": 5, "col": 5, "state": 7, "rank": -1},  # below the support floor
                    ]
                },
                f,
            )
        return path

    def test_orders_by_rank_and_skips_below_floor(self):
        with tempfile.TemporaryDirectory() as d:
            ids = ranked_features(self._stats(d))
            np.testing.assert_array_equal(ids, [(1 * 12 + 2) * 8 + 3, (0 * 12 + 0) * 8 + 1])

    def test_top_n_larger_than_the_ranked_set_clamps(self):
        with tempfile.TemporaryDirectory() as d:
            self.assertEqual(len(ranked_features(self._stats(d), top_n=500)), 2)


class LoadPositionsTest(unittest.TestCase):
    def test_reads_jsonl_rows(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "p.jsonl")
            with open(path, "w") as f:
                f.write(json.dumps({"game_id": 4, "ply": 0, "eval": -12, "active": [1, 2]}) + "\n")
                f.write("\n")
                f.write(json.dumps({"game_id": 4, "ply": 1, "eval": 30, "active": [3, 4]}) + "\n")
            game_ids, y, active = load_positions(path)
            np.testing.assert_array_equal(game_ids, [4, 4])
            np.testing.assert_array_equal(y, [-12.0, 30.0])
            np.testing.assert_array_equal(active, [[1, 2], [3, 4]])


class WeightsJsonTest(unittest.TestCase):
    def _fit(self):
        game_ids, y, active, _, _, n_features = synthetic(n_games=10)
        train, holdout = split_by_game(game_ids, 0.2, seed=0)
        ids = np.arange(n_features, dtype=np.int32)
        return weights_json(
            evaluate(active, y, ids, train, holdout, lam=1.0), ids, game_ids, train, holdout, 0, 0.2
        )

    def test_round_trips_through_json(self):
        doc = self._fit()
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "w.json")
            with open(path, "w") as f:
                json.dump(doc, f)
            back = json.load(open(path))
        self.assertEqual(back["meta"], doc["meta"])
        self.assertEqual(len(back["weights"]), len(doc["weights"]))
        for k, v in doc["weights"].items():
            self.assertAlmostEqual(back["weights"][k], v, places=9)

    def test_meta_counts_match_the_split(self):
        meta = self._fit()["meta"]
        self.assertEqual(meta["games_train"] + meta["games_holdout"], 10)
        self.assertEqual(meta["positions_train"] + meta["positions_holdout"], 80)
        self.assertEqual(meta["split_seed"], 0)

    def test_meta_records_what_the_split_needs_to_be_reproduced(self):
        meta = self._fit()["meta"]
        self.assertEqual(meta["holdout_frac"], 0.2)
        self.assertEqual(meta["games_total"], 10)
        self.assertEqual(meta["positions_total"], 80)

    @unittest.skipUnless(os.path.exists(COMMITTED_WEIGHTS), "no committed weights artifact")
    def test_committed_artifact_indices_are_in_range(self):
        doc = json.load(open(COMMITTED_WEIGHTS))
        keys = [int(k) for k in doc["weights"]]
        self.assertTrue(all(0 <= k < N_FEATURES for k in keys))
        self.assertEqual(len(set(keys)), len(keys))
        self.assertEqual(len(keys), doc["meta"]["top_n"])
        self.assertTrue(all(np.isfinite(list(doc["weights"].values()))))
        self.assertTrue(np.isfinite(doc["meta"]["bias"]))


class MainTest(unittest.TestCase):
    """End-to-end: the shipped artifact is the refit on ALL positions, not the train fit."""

    def _corpus(self, d, n_games):
        game_ids, y, active, _, _, n_features = synthetic(n_games=n_games)
        positions = os.path.join(d, "p.jsonl")
        with open(positions, "w") as f:
            for g, ev, act in zip(game_ids, y, active):
                # Pad to the real 1152-slot index space so main's full-set fit is valid.
                f.write(
                    json.dumps(
                        {"game_id": int(g), "ply": 0, "eval": float(ev), "active": act.tolist()}
                    )
                    + "\n"
                )
        stats = os.path.join(d, "stats.json")
        with open(stats, "w") as f:
            json.dump({"features": [{"row": 0, "col": 0, "state": 1, "rank": 0}]}, f)
        return positions, stats, game_ids, y, active, n_features

    def test_written_weights_are_refit_on_every_position(self):
        with tempfile.TemporaryDirectory() as d:
            positions, stats, _, y, active, _ = self._corpus(d, n_games=10)
            out = os.path.join(d, "w.json")
            main([positions, "--stats", stats, "--out", out, "--lambdas", "1.0"])
            doc = json.load(open(out))
            self.assertEqual(doc["meta"]["fit_on"], "all")
            ids = np.arange(N_FEATURES, dtype=np.int32)
            w, bias = ridge(design(active, ids), y, 1.0)
            self.assertAlmostEqual(doc["meta"]["bias"], float(bias), places=6)
            np.testing.assert_allclose(
                [doc["weights"][str(i)] for i in range(N_FEATURES)], w, atol=1e-6
            )

    def test_empty_holdout_is_an_error_not_a_crash(self):
        with tempfile.TemporaryDirectory() as d:
            positions, stats, _, _, _, _ = self._corpus(d, n_games=1)
            with self.assertRaises(SystemExit):
                main([positions, "--stats", stats, "--out", ""])


if __name__ == "__main__":
    unittest.main()
