"""NNUE v3.1 capacity probe: ridge-regress the hand-tuned static eval onto the
absolute (row, col, cell-state) indicator features, held out BY GAME.

Input is the JSONL emitted by V3FeatureMiner --emit-positions (one row per
replayed position: game_id, ply, eval, active[144]) plus the aggregate
nnue_v3_feature_stats.json (for the discrimination ranking / support floor).

    python3 -m python.v3.fit_capacity /tmp/nnue_v3_positions.jsonl

Ridge is closed-form -- w = (XtX + lam*I)^-1 Xt y -- so no scikit-learn.
The design matrix is rank-deficient BY CONSTRUCTION: exactly one of a cell's 8
state indicators is active, so each cell's 8 columns sum to the intercept
column (the dummy-variable trap, 144 times over). That is why lam is
load-bearing and why the solve goes through lstsq (minimum-norm on the
singular directions) instead of a plain inverse.
"""
import argparse
import json

import numpy as np

N_FEATURES = 1152  # 12*12 cells * 8 states, idx(r,c,s) = (r*12+c)*8 + s
DEFAULT_LAMBDAS = (0.0, 1.0, 10.0, 100.0, 1000.0, 10000.0, 100000.0)


def load_positions(path):
    """-> (game_ids[n], y float64[n], active int32[n, 144]).

    game_ids stay whatever the miner wrote (real games.db keys games by uuid
    string); they are only ever grouped, never arithmetic.
    """
    game_ids, evals, active = [], [], []
    with open(path) as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            row = json.loads(line)
            game_ids.append(row["game_id"])
            evals.append(row["eval"])
            active.append(row["active"])
    return (
        np.array(game_ids),
        np.array(evals, dtype=np.float64),
        np.array(active, dtype=np.int32),
    )


def ranked_features(stats_path, top_n=None):
    """Feature ids above the support floor, best discrimination first.

    Clamps to however many features actually cleared the floor -- top_n larger
    than the ranked set is not an error, it just means "all of them".
    """
    with open(stats_path) as f:
        stats = json.load(f)
    ranked = sorted((f for f in stats["features"] if f["rank"] >= 0), key=lambda f: f["rank"])
    if top_n is not None:
        ranked = ranked[:top_n]
    return np.array([(f["row"] * 12 + f["col"]) * 8 + f["state"] for f in ranked], dtype=np.int32)


def split_by_game(game_ids, holdout_frac=0.2, seed=0):
    """-> (train_mask, holdout_mask), split on WHOLE games.

    A position-level split leaks: positions inside one game share nearly all of
    their features and their eval trajectory, so held-out R2 would be measuring
    memorisation of the game, not of the eval.
    """
    games = np.unique(game_ids)
    rng = np.random.default_rng(seed)
    shuffled = rng.permutation(games)
    n_holdout = max(1, int(round(holdout_frac * len(games)))) if len(games) > 1 else 0
    holdout_games = set(shuffled[:n_holdout].tolist())
    holdout = np.array([g in holdout_games for g in game_ids])
    return ~holdout, holdout


def design(active, feature_ids, n_features=N_FEATURES):
    """Indicator design matrix over feature_ids, plus a trailing intercept column."""
    col_of = np.full(n_features, -1, dtype=np.int64)
    col_of[feature_ids] = np.arange(len(feature_ids))
    n, k = active.shape
    X = np.zeros((n, len(feature_ids) + 1))
    X[:, -1] = 1.0
    cols = col_of[active].ravel()
    rows = np.repeat(np.arange(n), k)
    keep = cols >= 0
    X[rows[keep], cols[keep]] = 1.0
    return X


def ridge(X, y, lam):
    """-> (weights over X's non-intercept columns, bias). Bias is UNpenalized."""
    d = X.shape[1]
    a = X.T @ X
    a[np.diag_indices(d)] += lam
    a[d - 1, d - 1] -= lam  # last column is the intercept -- never shrink it
    w = np.linalg.lstsq(a, X.T @ y, rcond=None)[0]
    return w[:-1], w[-1]


def r2(y, pred):
    ss_tot = float(np.sum((y - y.mean()) ** 2))
    if ss_tot == 0.0:
        return 1.0 if np.allclose(y, pred) else 0.0
    return 1.0 - float(np.sum((y - pred) ** 2)) / ss_tot


def evaluate(active, y, feature_ids, train, holdout, lam):
    """Fit on train, score on holdout. -> dict with the numbers the report quotes."""
    xt = design(active, feature_ids)
    w, bias = ridge(xt[train], y[train], lam)
    pred = xt[:, :-1] @ w + bias
    resid = y[holdout] - pred[holdout]
    return {
        "lambda": lam,
        "n_features": len(feature_ids),
        "r2_holdout": r2(y[holdout], pred[holdout]),
        "r2_train": r2(y[train], pred[train]),
        "corr_holdout": (
            float(np.corrcoef(y[holdout], pred[holdout])[0, 1]) if len(y[holdout]) > 1 else 1.0
        ),
        "mae_holdout": float(np.mean(np.abs(resid))),
        "resid_p10": float(np.percentile(resid, 10)),
        "resid_p90": float(np.percentile(resid, 90)),
        "bias": float(bias),
        "weights": w,
    }


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("positions", nargs="?", default="/tmp/nnue_v3_positions.jsonl")
    p.add_argument("--stats", default="nnue_v3_feature_stats.json")
    p.add_argument("--top-n", type=int, default=500)
    p.add_argument("--holdout-frac", type=float, default=0.2)
    p.add_argument("--seed", type=int, default=0)
    p.add_argument("--lambdas", type=float, nargs="+", default=list(DEFAULT_LAMBDAS))
    args = p.parse_args(argv)

    game_ids, y, active = load_positions(args.positions)
    train, holdout = split_by_game(game_ids, args.holdout_frac, args.seed)
    top = ranked_features(args.stats, args.top_n)
    full = np.arange(N_FEATURES, dtype=np.int32)
    print(
        f"positions {len(y)} ({int(train.sum())} train / {int(holdout.sum())} holdout), "
        f"games {len(np.unique(game_ids))} "
        f"({len(np.unique(game_ids[train]))} / {len(np.unique(game_ids[holdout]))}), "
        f"top-n {args.top_n} -> {len(top)} ranked features"
    )
    print(f"{'set':>10} {'lambda':>10} {'r2_hold':>9} {'r2_train':>9} {'corr':>7} {'MAE':>10}")
    for name, ids in (("top-%d" % len(top), top), ("full-1152", full)):
        for lam in args.lambdas:
            m = evaluate(active, y, ids, train, holdout, lam)
            print(
                f"{name:>10} {lam:>10.4g} {m['r2_holdout']:>9.4f} {m['r2_train']:>9.4f} "
                f"{m['corr_holdout']:>7.4f} {m['mae_holdout']:>10.1f}"
            )


if __name__ == "__main__":
    main()
