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

from python.v2 import train_v2

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
