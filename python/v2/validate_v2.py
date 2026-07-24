"""Validate NNUE v2 learning + board-size independence, write a committed report.

Answers two questions (see docs/plans/20260724-v2-validate-learning.md):
  1. Does the v2 sparse-pattern representation learn useful signal?
  2. Is the trained model board-size independent (finite, shape-correct evals on
     NON-12x12 boards with no code changes)?

Plus an honest, apples-to-oranges v1-vs-v2 comparison. Reuse only, no new deps.
"""
import os
import sys

import torch

_HERE = os.path.dirname(os.path.abspath(__file__))
_REPO_ROOT = os.path.abspath(os.path.join(_HERE, "..", ".."))
if _REPO_ROOT not in sys.path:
    sys.path.insert(0, _REPO_ROOT)

import math

from python.v2 import train_v2
from python.v2 import extract_examples
from python.v2.dense_features import extract_dense_features
from python.v2.pattern_contract import Board, Cell, CellKind

# Board sizes for the independence proof: >=2 non-12x12 plus 12x12 sanity anchor.
PROOF_SIZES = [(5, 5), (5, 7), (7, 9), (9, 9), (12, 12)]

# Real training config (matches train_v2 defaults; stated explicitly for the report).
V2_W = 1024
V2_EPOCHS = 20
V2_BATCH = 64
V2_LR = 1e-3
V2_SEED = 0
V2_VAL_FRAC = 0.2


def _split_indices(n, val_frac, seed):
    """Replicate train_v2.train_model's held-out split so we can score the same
    val set (train_model does the split internally and does not expose it)."""
    gen = torch.Generator().manual_seed(seed)
    perm = torch.randperm(n, generator=gen).tolist()
    n_val = int(round(n * val_frac))
    val_idx = set(perm[:n_val])
    train_ex = [i for i in range(n) if i not in val_idx]
    val_ex = [i for i in range(n) if i in val_idx]
    return train_ex, val_ex


def _v2_predictions(model, examples, batch_size=1024):
    """Model WDL predictions over `examples`, in order."""
    model.eval()
    preds = []
    with torch.no_grad():
        for start in range(0, len(examples), batch_size):
            batch = train_v2.collate(examples[start:start + batch_size])
            out = model(batch["stm_ids"], batch["stm_off"], batch["stm_w"],
                        batch["nstm_ids"], batch["nstm_off"], batch["nstm_w"],
                        batch["dense"])
            preds.extend(out.tolist())
    return preds


def run_v2_training(dict_path=None, examples_path=None):
    """Train the real v2 model and return learning curves + summary metrics.

    Returns a dict: model, curve [(epoch, train_mse, val_mse)], final_train_mse,
    final_val_mse, dict_size, dataset_size, const_floor (constant-predictor val
    MSE), dir_acc (directional accuracy on the val split).
    """
    dict_path = dict_path or train_v2._DEFAULT_DICT
    examples_path = examples_path or train_v2._DEFAULT_EXAMPLES

    num_patterns = train_v2.read_num_patterns(dict_path)
    examples = train_v2.load_examples(examples_path, dict_path=dict_path)

    curve = []
    model, final_train_mse, final_val_mse = train_v2.train_model(
        examples, num_patterns, W=V2_W, epochs=V2_EPOCHS, batch_size=V2_BATCH,
        lr=V2_LR, seed=V2_SEED, val_frac=V2_VAL_FRAC,
        on_epoch=lambda e, tr, va: curve.append((e, tr, va)))

    train_i, val_i = _split_indices(len(examples), V2_VAL_FRAC, V2_SEED)
    train_wdl = [examples[i]["wdl"] for i in train_i]
    val_wdl = [examples[i]["wdl"] for i in val_i]
    # Constant-predictor floor: best you can do without features = predict mean.
    mean_train = sum(train_wdl) / len(train_wdl)
    const_floor = sum((w - mean_train) ** 2 for w in val_wdl) / len(val_wdl)

    val_ex = [examples[i] for i in val_i]
    preds = _v2_predictions(model, val_ex)
    correct = sum((p > 0.5) == (w > 0.5) for p, w in zip(preds, val_wdl))
    dir_acc = correct / len(val_wdl)

    return {
        "model": model,
        "curve": curve,
        "final_train_mse": final_train_mse,
        "final_val_mse": final_val_mse,
        "dict_size": num_patterns,
        "dataset_size": len(examples),
        "const_floor": const_floor,
        "dir_acc": dir_acc,
    }


def run_v1_baseline():
    """Train the repo-root v1 net (seed=0) for an honest cross-comparison.

    Returns a dict with v1 best_val_mse, train_mse, dir_acc (sign(pred) vs
    sign(target) on its val split), const_floor, and dataset_size. If
    `dataset.json` is missing, returns {available: False, reason: ...}.
    """
    try:
        import numpy as np
        import train as v1  # repo-root v1 trainer
        if not os.path.exists(v1.DATASET):
            return {"available": False, "reason": "dataset.json not found"}

        X, y = v1.load_data()
        Xtr, ytr, Xval, yval = v1.split(X, y)
        best_val, train_mse, weights = v1.train(Xtr, ytr, Xval, yval, seed=v1.SEED)

        W1, b1, w2, b2 = weights
        pred = v1.clipped_relu(Xval @ W1.T + b1) @ w2 + b2
        # No draws in this corpus; sign(0) counted as a miss is fine (target != 0).
        correct = int(np.sum(np.sign(pred) == np.sign(yval)))
        dir_acc = correct / len(yval)
        const_floor = float(((yval - ytr.mean()) ** 2).mean())

        return {
            "available": True,
            "best_val_mse": best_val,
            "train_mse": train_mse,
            "dir_acc": dir_acc,
            "const_floor": const_floor,
            "dataset_size": len(y),
        }
    except Exception as exc:  # any v1 import/data failure degrades gracefully
        return {"available": False, "reason": f"v1 baseline unavailable: {exc}"}


def synth_board(rows, cols):
    """A deterministic board of any size for the size-independence proof.

    Places both player bases (owner 1 at (0,0), owner 2 at the far corner, per
    dense_features convention) and a dense interior cluster of NORMAL_SELF /
    NORMAL_OPPONENT cells plus a neutral, so interior 5x5 windows can match the
    12x12-mined dictionary. Every position is derived from rows/cols — no
    hardcoded 12.
    """
    board = Board(rows, cols)
    board.set_cell(0, 0, Cell(1, CellKind.BASE))
    board.set_cell(rows - 1, cols - 1, Cell(2, CellKind.BASE))

    cr, cc = rows // 2, cols // 2
    for dr in range(-1, 2):
        for dc in range(-1, 2):
            r, c = cr + dr, cc + dc
            if not board.is_valid_pos(r, c) or (r, c) in ((0, 0), (rows - 1, cols - 1)):
                continue
            owner = 1 if (dr + dc) % 2 == 0 else 2
            board.set_cell(r, c, Cell(owner, CellKind.NORMAL))
    # A neutral just outside the cluster, still interior when the board allows it.
    nr, nc = min(cr + 2, rows - 1), cc
    board.set_cell(nr, nc, Cell(0, CellKind.NEUTRAL))
    return board


def eval_board(model, board, pattern_to_id):
    """Forward-evaluate `board` through `model` with no code changes per size.

    Returns {rows, cols, stm_matched, nstm_matched, eval, finite}. `stm_matched`
    / `nstm_matched` are counts of dictionary-matched pattern occurrences (small
    boards match few because the dictionary was 12x12-mined).
    """
    stm_counts = extract_examples.pattern_counts(board, 1, pattern_to_id)
    nstm_counts = extract_examples.pattern_counts(board, 2, pattern_to_id)
    grid = extract_examples.board_to_grid(board)
    dense = extract_dense_features(grid, active_player=1, turn_number=0,
                                   rows=board.rows, cols=board.cols)
    example = {
        "stm_pattern_counts": stm_counts,
        "nstm_pattern_counts": nstm_counts,
        "dense": dense,
        "wdl": 0.0,  # unused by forward; present so collate accepts the example
    }
    batch = train_v2.collate([example])
    model.eval()
    with torch.no_grad():
        out = model(batch["stm_ids"], batch["stm_off"], batch["stm_w"],
                    batch["nstm_ids"], batch["nstm_off"], batch["nstm_w"],
                    batch["dense"])
    assert tuple(out.shape) == (1,), f"expected scalar eval, got shape {tuple(out.shape)}"
    value = out.item()
    return {
        "rows": board.rows,
        "cols": board.cols,
        "stm_matched": sum(stm_counts.values()),
        "nstm_matched": sum(nstm_counts.values()),
        "eval": value,
        "finite": math.isfinite(value),
    }


def board_size_proof(model, pattern_to_id, sizes=None):
    """Evaluate `model` on several board sizes; assert every eval is finite.

    Returns the per-size rows for the report. Runs at least two non-12x12 sizes.
    """
    sizes = sizes or PROOF_SIZES
    rows = []
    for r, c in sizes:
        result = eval_board(model, synth_board(r, c), pattern_to_id)
        assert result["finite"], f"non-finite eval at {r}x{c}: {result['eval']}"
        rows.append(result)
    return rows
