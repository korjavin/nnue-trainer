"""Generate the Java<->Python parity fixture for the NNUE v3 NET evaluator.

Reuses the boards already committed in the linear evaluator's fixture
(src/test/resources/v3/eval_parity_fixture.json) -- real corpus positions, picked to
cover every PatternContract state -- and re-scores them with the net's own arithmetic:

    acc = b1 + w1[:, active].sum(axis=1);  eval = w2 @ relu(acc) + b2

    python3 scripts/gen_v3_net_fixture.py                      # against nnue_v3_net.json
    python3 scripts/gen_v3_net_fixture.py --synth 4            # against a deterministic stub net

V3NetParityTest replays the same boards through NNUEv3NetEvaluator and must match. If it
stops matching after a retrain, regenerate -- the expected scores belong to a specific
weights file, which the fixture names in meta.weights.

--synth exists because the runtime landed before the real weights: it writes a
deterministic H-hidden net to src/test/resources/v3/net_synth_weights.json (no RNG, so
the file is reproducible from this source alone) and points the fixture at it. When the
real nnue_v3_net.json lands, rerun WITHOUT --synth and commit both files.
"""
import argparse
import json
import os
import sys

import numpy as np

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _REPO_ROOT not in sys.path:
    sys.path.insert(0, _REPO_ROOT)

from python.v2.pattern_contract import Board, Cell, CellKind, PatternContract  # noqa: E402

BOARD = 12
STATES = 8
N_FEATURES = BOARD * BOARD * STATES

_RES = os.path.join(_REPO_ROOT, "src", "test", "resources", "v3")
_BOARDS = os.path.join(_RES, "eval_parity_fixture.json")
_OUT = os.path.join(_RES, "net_parity_fixture.json")
_SYNTH = os.path.join(_RES, "net_synth_weights.json")
_REAL = os.path.join(_REPO_ROOT, "nnue_v3_net.json")


def active_of(board, stm):
    """The 144 active feature ids of (board, stm) -- mirrors V3FeatureMiner.activeFeatures."""
    return [
        (r * BOARD + c) * STATES + PatternContract.get_symbol(board.get_cell(r, c), stm)
        for r in range(BOARD)
        for c in range(BOARD)
    ]


def board_from_cells(cells):
    b = Board(BOARD, BOARD)
    for cell in cells:
        b.set_cell(cell["r"], cell["c"], Cell(cell["owner"], CellKind[cell["kind"]]))
    return b


def synth_net(hidden):
    """A deterministic stub net -- no RNG, so this source alone reproduces the file."""
    i = np.arange(N_FEATURES, dtype=np.float64)
    h = np.arange(hidden, dtype=np.float64)
    w1 = ((np.outer(h + 1, i) % 97.0) - 48.0) / 100.0
    return {
        "meta": {
            "arch": "1152-%d-1" % hidden,
            "hidden": hidden,
            "activation": "relu",
            "features": N_FEATURES,
            "score_units": "hand_tuned",
            "synthetic": True,
            "note": "deterministic stub from scripts/gen_v3_net_fixture.py --synth; NOT a fit",
        },
        "w1": w1.tolist(),
        "b1": (h - 1.5).tolist(),
        "w2": ((h + 1) * 100.0).tolist(),
        "b2": 12.5,
    }


def load_net(path):
    """-> (w1 [H,1152], b1 [H], w2 [H], b2). Validated the same way the Java loader does."""
    with open(path) as f:
        doc = json.load(f)
    hidden = int(doc["meta"]["hidden"])
    if int(doc["meta"]["features"]) != N_FEATURES:
        raise SystemExit("%s: meta.features != %d" % (path, N_FEATURES))
    w1 = np.array(doc["w1"], dtype=np.float64)
    b1 = np.array(doc["b1"], dtype=np.float64)
    w2 = np.array(doc["w2"], dtype=np.float64)
    if w1.shape != (hidden, N_FEATURES) or b1.shape != (hidden,) or w2.shape != (hidden,):
        raise SystemExit(
            "%s: shape mismatch for hidden=%d: w1%s b1%s w2%s"
            % (path, hidden, w1.shape, b1.shape, w2.shape)
        )
    if not (np.isfinite(w1).all() and np.isfinite(b1).all() and np.isfinite(w2).all()):
        raise SystemExit("%s: non-finite weights" % path)
    return w1, b1, w2, float(doc["b2"])


def predict(w1, b1, w2, b2, active):
    acc = b1 + w1[:, np.asarray(active, dtype=np.int32)].sum(axis=1)
    return float(w2 @ np.maximum(acc, 0.0) + b2)


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--weights", default=None, help="net weights json (default: nnue_v3_net.json)")
    p.add_argument("--synth", type=int, metavar="H",
                   help="write and use a deterministic H-hidden stub net instead")
    p.add_argument("--boards", default=_BOARDS)
    p.add_argument("--out", default=_OUT)
    args = p.parse_args(argv)

    weights = args.weights
    if args.synth:
        if args.synth <= 0:
            raise SystemExit("--synth H must be positive, got %d" % args.synth)
        weights = weights or _SYNTH
        with open(weights, "w") as f:
            json.dump(synth_net(args.synth), f, indent=1, sort_keys=True, allow_nan=False)
            f.write("\n")
        print("wrote synthetic net %s (hidden=%d)" % (weights, args.synth))
    weights = weights or _REAL
    if not os.path.exists(weights):
        raise SystemExit("%s not found -- pass --weights, or --synth H for a stub net" % weights)

    w1, b1, w2, b2 = load_net(weights)
    with open(args.boards) as f:
        src = json.load(f)

    fixtures = []
    for fx in src["fixtures"]:
        board = board_from_cells(fx["cells"])
        fixtures.append(
            {
                "name": fx["name"],
                "stm": fx["stm"],
                "cells": fx["cells"],
                "expected": predict(w1, b1, w2, b2, active_of(board, fx["stm"])),
            }
        )

    doc = {
        "meta": {
            # Repo-root-relative: the Java test loads exactly the file these scores came from.
            "weights": os.path.relpath(os.path.abspath(weights), _REPO_ROOT),
            "hidden": int(len(b1)),
            "boards": os.path.basename(args.boards),
        },
        "fixtures": fixtures,
    }
    with open(args.out, "w") as f:
        json.dump(doc, f, indent=1, sort_keys=True, allow_nan=False)
        f.write("\n")
    print("wrote %s (%d entries, hidden=%d)" % (args.out, len(fixtures), len(b1)))
    for fx in fixtures:
        print("  %-48s %10.4f" % (fx["name"], fx["expected"]))


if __name__ == "__main__":
    main()
