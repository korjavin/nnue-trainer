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
import math

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
        for lineno, line in enumerate(f, 1):
            line = line.strip()
            if not line:
                continue
            # Name the file AND the line: the realistic corruption is a miner killed mid-write, and
            # json's own "line 1 column 31" refers to the fragment, not to the position in the file.
            try:
                row = json.loads(line)
                game_ids.append(row["game_id"])
                evals.append(row["eval"])
                active.append(row["active"])
            except (ValueError, KeyError, TypeError) as exc:
                raise SystemExit("%s:%d: %s" % (path, lineno, exc)) from exc
    # A ragged `active` (a non-12x12 game slipping the miner's board filter) otherwise surfaces as
    # numpy's "inhomogeneous shape" deep inside np.array, pointing at numpy rather than the corpus.
    try:
        idx = np.array(active, dtype=np.int32)
    except ValueError as exc:
        raise SystemExit(
            "%s: every row needs the same number of active features (%s)" % (path, exc)
        ) from exc
    if idx.size and idx.ndim != 2:
        raise SystemExit("%s: expected one active list per row, got shape %s" % (path, idx.shape))
    check_feature_ids(idx, path)
    return np.array(game_ids), np.array(evals, dtype=np.float64), idx


def check_feature_ids(ids, where):
    """Both index paths into `design`'s col_of run through here.

    numpy reads a NEGATIVE index from the end -- feature -1 would silently alias feature 1151 and
    bind a weight to the wrong cell. Only an out-of-range POSITIVE index raises on its own.
    """
    if ids.size and (ids.min() < 0 or ids.max() >= N_FEATURES):
        raise SystemExit(
            "feature index out of range in %s: [%d, %d] not within [0, %d)"
            % (where, ids.min(), ids.max(), N_FEATURES)
        )


def ranked_features(stats_path, top_n=None, n_positions=None):
    """Feature ids above the support floor, best discrimination first.

    Clamps to however many features actually cleared the floor -- top_n larger
    than the ranked set is not an error, it just means "all of them".

    n_positions cross-checks provenance: the ranking is only meaningful for the corpus it was
    mined from, so re-mining the JSONL without re-mining the stats would otherwise silently
    select the wrong top-N set. Both artifacts already count their positions.

    SELECTION BIAS, disclosed rather than fixed: the miner's discrimination ranking is computed
    over EVERY mined position, holdout included, so a top-N holdout R2 is measured on games whose
    labels helped choose the feature set. The shipped artifact is the full-1152 fit, which selects
    nothing and is unaffected; the top-N rows of the sweep table in docs/nnue-v3-capacity.md are
    optimistic by an unmeasured amount. Ranking from train rows only would fix it, at the cost of
    a ranking that no longer matches the committed nnue_v3_feature_stats.json.
    """
    with open(stats_path) as f:
        stats = json.load(f)
    mined = stats.get("meta", {}).get("positions")
    if n_positions is not None and mined is not None and mined != n_positions:
        raise SystemExit(
            "%s was mined from %d positions but the JSONL has %d -- re-mine both from the same DB"
            % (stats_path, mined, n_positions)
        )
    ranked = sorted((f for f in stats["features"] if f["rank"] >= 0), key=lambda f: f["rank"])
    if top_n is not None:
        ranked = ranked[:top_n]
    ids = np.array(
        [(f["row"] * 12 + f["col"]) * 8 + f["state"] for f in ranked], dtype=np.int32
    )
    check_feature_ids(ids, stats_path)
    # Duplicates would be silent too: col_of is last-wins, so one feature loses its column and the
    # emitted weights dict comes out shorter than meta.top_n claims.
    if len(np.unique(ids)) != len(ids):
        raise SystemExit("duplicate (row, col, state) entries in %s" % stats_path)
    return ids


def split_by_game(game_ids, holdout_frac=0.2, seed=0):
    """-> (train_mask, holdout_mask), split on WHOLE games.

    A position-level split leaks: positions inside one game share nearly all of
    their features and their eval trajectory, so held-out R2 would be measuring
    memorisation of the game, not of the eval.
    """
    games = np.unique(game_ids)
    rng = np.random.default_rng(seed)
    shuffled = rng.permutation(games)
    n_holdout = int(round(holdout_frac * len(games)))
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
        # NaN, not 1.0: a one-position holdout has no correlation to report, and this value is
        # written into nnue_v3_weights.json's meta and quoted by the report.
        "corr_holdout": (
            float(np.corrcoef(y[holdout], pred[holdout])[0, 1])
            if len(y[holdout]) > 1
            else float("nan")
        ),
        "mae_holdout": float(np.mean(np.abs(resid))),
        "resid_p10": float(np.percentile(resid, 10)),
        "resid_p90": float(np.percentile(resid, 90)),
        # The tail, not the middle 80%: MAE exceeds |p90|, so the error lives out here.
        "resid_abs_p99": float(np.percentile(np.abs(resid), 99)),
        "resid_abs_max": float(np.max(np.abs(resid))),
        "bias": float(bias),
        "weights": w,
    }


def weights_json(fit, feature_ids, game_ids, train, holdout, seed, holdout_frac):
    """The warm start consumed by nnue-trainer-aov / -1uz, plus repro metadata.

    fit["weights"]/["bias"] are the model to SHIP (refit on every position); the
    r2/corr/mae fields are the measurement from the train-only fit at the same
    lambda, on games that fit never saw.
    """
    return {
        "meta": {
            "lambda": fit["lambda"],
            "top_n": len(feature_ids),
            "split_seed": seed,
            "holdout_frac": holdout_frac,
            "fit_on": "all",  # weights use every game; the metrics below do not
            # games_USED, not games_total: nnue_v3_feature_stats.json counts every row in the
            # games table as games_total (502) and the replayable subset as games_used (446).
            # The positions file only ever contains the latter, so it must not claim the former's
            # name -- the obvious provenance cross-check between the two artifacts reads this key.
            "games_used": len(np.unique(game_ids)),
            "positions_total": len(game_ids),
            "games_train": len(np.unique(game_ids[train])),
            "games_holdout": len(np.unique(game_ids[holdout])),
            "positions_train": int(train.sum()),
            "positions_holdout": int(holdout.sum()),
            "r2_holdout": fit["r2_holdout"],
            "r2_train": fit["r2_train"],
            # null, not NaN: json.dump writes a bare NaN token by default, which is not JSON and
            # which Jackson rejects outright -- a degenerate split would otherwise ship a warm
            # start that aov/1uz cannot parse. Reachable via a one-position holdout, or via a
            # constant prediction making corrcoef NaN.
            "corr_holdout": (
                fit["corr_holdout"] if math.isfinite(fit["corr_holdout"]) else None
            ),
            "mae_holdout": fit["mae_holdout"],
            "bias": fit["bias"],
            "n_features_total": N_FEATURES,
        },
        "weights": {str(int(i)): float(w) for i, w in zip(feature_ids, fit["weights"])},
    }


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("positions", nargs="?", default="/tmp/nnue_v3_positions.jsonl")
    p.add_argument("--stats", default="nnue_v3_feature_stats.json")
    p.add_argument("--top-n", type=int, default=500)
    p.add_argument("--holdout-frac", type=float, default=0.2)
    p.add_argument("--seed", type=int, default=0)
    p.add_argument("--lambdas", type=float, nargs="+", default=list(DEFAULT_LAMBDAS))
    # Defaults to NOT writing: the documented methodology is a lambda/seed sweep, and a default of
    # nnue_v3_weights.json would let any exploratory `--seed 3` run silently replace the committed
    # warm start that aov/1uz initialize from. Writing it is an explicit `--out`.
    p.add_argument("--out", default="", help="path to write weights to; empty = sweep only")
    args = p.parse_args(argv)

    # Negative values are the dangerous typo here: both are used as slice bounds, so
    # `--top-n -5` silently drops the WORST 5 features and `--holdout-frac -0.5` silently
    # holds out the complement -- neither errors, and meta then records a split that never ran.
    if not 0.0 < args.holdout_frac < 1.0:
        raise SystemExit("--holdout-frac must be in (0, 1), got %g" % args.holdout_frac)
    if args.top_n <= 0:
        raise SystemExit("--top-n must be positive, got %d" % args.top_n)
    # A negative lambda SUBTRACTS from the diagonal of an already 144-fold rank-deficient XtX --
    # lstsq still returns something, just an arbitrary point on the degenerate direction.
    if any(lam < 0.0 for lam in args.lambdas):
        raise SystemExit("--lambdas must be non-negative, got %s" % args.lambdas)

    game_ids, y, active = load_positions(args.positions)
    if len(y) == 0:
        raise SystemExit(
            "no positions in %s -- did V3FeatureMiner run with --emit-positions?" % args.positions
        )
    train, holdout = split_by_game(game_ids, args.holdout_frac, args.seed)
    if not holdout.any() or not train.any():
        # Name the real cause: the split is on WHOLE games and rounds, so the failure is
        # `round(frac * games) == 0` (or `== games`), which a 2-game corpus hits at the default
        # 0.2. Deliberately not a silent one-game fallback -- see the --holdout-frac row in
        # docs/nnue-v3-capacity.md.
        raise SystemExit(
            "empty split: %d train / %d holdout positions -- "
            "--holdout-frac %g rounds to %d of %d whole games"
            % (
                train.sum(),
                holdout.sum(),
                args.holdout_frac,
                int(round(args.holdout_frac * len(np.unique(game_ids)))),
                len(np.unique(game_ids)),
            )
        )
    top = ranked_features(args.stats, args.top_n, len(y))
    full = np.arange(N_FEATURES, dtype=np.int32)
    print(
        f"positions {len(y)} ({int(train.sum())} train / {int(holdout.sum())} holdout), "
        f"games {len(np.unique(game_ids))} "
        f"({len(np.unique(game_ids[train]))} / {len(np.unique(game_ids[holdout]))}), "
        f"top-n {args.top_n} -> {len(top)} ranked features"
    )
    print(
        f"{'set':>10} {'lambda':>10} {'r2_hold':>9} {'r2_train':>9} {'corr':>7} {'MAE':>10} "
        f"{'p10':>9} {'p90':>9} {'|p99|':>9} {'|max|':>9}"
    )
    best = None
    for name, ids in (("top-%d" % len(top), top), ("full-1152", full)):
        for lam in args.lambdas:
            m = evaluate(active, y, ids, train, holdout, lam)
            print(
                f"{name:>10} {lam:>10.4g} {m['r2_holdout']:>9.4f} {m['r2_train']:>9.4f} "
                f"{m['corr_holdout']:>7.4f} {m['mae_holdout']:>10.1f} "
                f"{m['resid_p10']:>9.1f} {m['resid_p90']:>9.1f} "
                f"{m['resid_abs_p99']:>9.1f} {m['resid_abs_max']:>9.1f}"
            )
            # isfinite before the compare: NaN loses every `>` test, so seeding `best` with a
            # degenerate first candidate would pin it there and ship it as the winner.
            if np.isfinite(m["r2_holdout"]) and (
                best is None or m["r2_holdout"] > best[0]["r2_holdout"]
            ):
                best = (m, ids)
    if args.out and best is not None:
        # The holdout's job is to MEASURE and to pick lambda; the shipped warm start then
        # refits at that lambda on every position -- withholding 20% of a 446-game corpus
        # from the artifact aov/1uz initialize from would just be a worse initialization.
        w, bias = ridge(design(active, best[1]), y, best[0]["lambda"])
        final = dict(best[0], weights=w, bias=float(bias))
        doc = weights_json(final, best[1], game_ids, train, holdout, args.seed, args.holdout_frac)
        with open(args.out, "w") as f:
            # allow_nan=False for the same reason: fail loudly here rather than write a file the
            # Java consumer cannot read.
            json.dump(doc, f, indent=1, sort_keys=True, allow_nan=False)
            f.write("\n")
        print(
            f"wrote {args.out}: lambda={doc['meta']['lambda']:g} "
            f"n={doc['meta']['top_n']} r2_holdout={doc['meta']['r2_holdout']:.4f}"
        )


if __name__ == "__main__":
    main()
