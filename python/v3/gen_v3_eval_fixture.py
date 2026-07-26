"""Generate the Java<->Python v3 eval parity fixture.

Takes real mined positions (the JSONL from `V3FeatureMiner --emit-positions`, the
same file fit_capacity.py regresses on), reconstructs each board from its active
feature ids, predicts with the FITTER'S OWN arithmetic (fit_capacity.design plus
the shipped weights), and writes src/test/resources/v3/eval_parity_fixture.json.

    python3 -m python.v3.gen_v3_eval_fixture /tmp/nnue_v3_positions.jsonl

V3EvalParityTest replays the same boards through NNUEv3Evaluator and must match.
If it stops matching after a refit, regenerate this fixture -- the expected
scores belong to a specific nnue_v3_weights.json.

Real corpus boards, not synthetic ones: a perspective or index mismatch shows up
on the positions the model will actually see, not on a hand-built diagram.
"""
import argparse
import json
import os
import sys

import numpy as np

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
if _REPO_ROOT not in sys.path:
    sys.path.insert(0, _REPO_ROOT)

from python.v3.fit_capacity import N_FEATURES, design, load_positions  # noqa: E402
from python.v2.pattern_contract import Board, Cell, CellKind, PatternContract  # noqa: E402

BOARD = 12
_OUT_PATH = os.path.join(_REPO_ROOT, "src", "test", "resources", "v3", "eval_parity_fixture.json")
_WEIGHTS = os.path.join(_REPO_ROOT, "nnue_v3_weights.json")

# Inverse of PatternContract.get_symbol for stm_owner == 1. Reconstruction is only
# defined up to which concrete owner plays the *_SELF role, which is exactly what
# stm picks out -- so boards come back as "side 1 to move".
_STATE_TO_CELL = {
    PatternContract.EMPTY: (0, CellKind.EMPTY),
    PatternContract.NEUTRAL: (0, CellKind.NEUTRAL),
    PatternContract.BASE_SELF: (1, CellKind.BASE),
    PatternContract.BASE_OPPONENT: (2, CellKind.BASE),
    PatternContract.NORMAL_SELF: (1, CellKind.NORMAL),
    PatternContract.NORMAL_OPPONENT: (2, CellKind.NORMAL),
    PatternContract.FORTIFIED_SELF: (1, CellKind.FORTIFIED),
    PatternContract.FORTIFIED_OPPONENT: (2, CellKind.FORTIFIED),
}


def board_from_active(active):
    """active[144] (STM-normalized ids) -> a Board whose stm=1 view reproduces them."""
    if len(active) != BOARD * BOARD:
        raise SystemExit("expected %d active features, got %d" % (BOARD * BOARD, len(active)))
    b = Board(BOARD, BOARD)
    for f in active:
        cell, state = divmod(int(f), 8)
        r, c = divmod(cell, BOARD)
        owner, kind = _STATE_TO_CELL[state]
        b.set_cell(r, c, Cell(owner, kind))
    return b


def active_of(board, stm):
    return [
        (r * BOARD + c) * 8 + PatternContract.get_symbol(board.get_cell(r, c), stm)
        for r in range(BOARD)
        for c in range(BOARD)
    ]


def load_weights(path):
    """-> (feature_ids int32[k], w float64[k], bias). Column order = the fit's."""
    with open(path) as f:
        doc = json.load(f)
    ids = sorted(int(k) for k in doc["weights"])
    if ids and (ids[0] < 0 or ids[-1] >= N_FEATURES):
        raise SystemExit("%s: feature id outside [0, %d)" % (path, N_FEATURES))
    w = np.array([doc["weights"][str(i)] for i in ids], dtype=np.float64)
    return np.array(ids, dtype=np.int32), w, float(doc["meta"]["bias"])


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("positions", nargs="?", default="/tmp/nnue_v3_positions.jsonl")
    p.add_argument("--weights", default=_WEIGHTS)
    p.add_argument("--n", type=int, default=8, help="boards to sample")
    p.add_argument("--out", default=_OUT_PATH)
    args = p.parse_args(argv)
    if args.n <= 0:
        raise SystemExit("--n must be positive, got %d" % args.n)

    game_ids, evals, active = load_positions(args.positions)
    if len(game_ids) < args.n:
        raise SystemExit("%s: only %d positions, need %d" % (args.positions, len(game_ids), args.n))
    ids, w, bias = load_weights(args.weights)

    # Deterministic (game, ply) order -- stable across reruns, no RNG to re-seed. Distinct boards
    # only: every game's opening plies are the SAME board, so a plain stride would sample one
    # position eight times and prove nothing eight times over.
    order = sorted(range(len(game_ids)), key=lambda i: (str(game_ids[i]), i))
    states = [frozenset(int(f) % 8 for f in active[i]) for i in range(len(active))]
    uniq, seen = [], set()
    for j in order:
        key = tuple(active[j])
        if key not in seen:
            seen.add(key)
            uniq.append(j)
    if len(uniq) < args.n:
        raise SystemExit("%s: only %d distinct boards, need %d" % (args.positions, len(uniq), args.n))

    # Rarest states first: NEUTRAL is on ~3% of mined boards, so a plain stride can miss a whole
    # PatternContract state and leave its index mapping untested across languages.
    picks = []
    for state in sorted(range(8), key=lambda s: sum(s in st for st in states)):
        if len(picks) >= args.n or any(state in states[j] for j in picks):
            continue
        j = next((j for j in uniq if state in states[j] and j not in picks), None)
        if j is not None:  # else the corpus itself never shows this state
            picks.append(j)
    # Fill the rest by striding the distinct boards: different games, mixed opening/endgame.
    for j in (uniq[(i * len(uniq)) // args.n] for i in range(args.n)):
        if len(picks) >= args.n:
            break
        if j not in picks:
            picks.append(j)
    picks += [j for j in uniq if j not in picks][: args.n - len(picks)]

    boards = [board_from_active(active[i]) for i in picks]
    fixtures = []
    for stm in (1, 2):
        rows = np.array([active_of(b, stm) for b in boards], dtype=np.int32)
        if stm == 1:
            # The reconstruction must round-trip, or every expected score below is for a
            # board that is not the one the miner saw.
            assert (rows == active[picks]).all(), "board reconstruction lost information"
        preds = design(rows, ids)[:, :-1] @ w + bias
        for b, i, pred in zip(boards, picks, preds):
            fixtures.append(
                {
                    "name": "%s@%d/stm%d" % (game_ids[i], i, stm),
                    "stm": stm,
                    "label_eval": int(evals[i]) if stm == 1 else None,
                    "cells": [
                        {"r": r, "c": c, "owner": b.get_cell(r, c).owner,
                         "kind": b.get_cell(r, c).kind.name}
                        for r in range(BOARD)
                        for c in range(BOARD)
                        if b.get_cell(r, c).kind != CellKind.EMPTY
                    ],
                    "expected": float(pred),
                }
            )

    doc = {
        "meta": {
            "positions": os.path.basename(args.positions),
            "weights": os.path.basename(args.weights),
            "bias": bias,
            "n_features": len(ids),
        },
        "fixtures": fixtures,
    }
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w") as f:
        json.dump(doc, f, indent=1, sort_keys=True, allow_nan=False)
        f.write("\n")
    print("wrote %s (%d entries from %d boards)" % (args.out, len(fixtures), len(boards)))
    for fx in fixtures:
        print("  %-48s %d cells  %10.2f" % (fx["name"], len(fx["cells"]), fx["expected"]))


if __name__ == "__main__":
    main()
