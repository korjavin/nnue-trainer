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


def _v2_predictions(model, examples, batch_size=1024):
    """Model WDL predictions over `examples`, in order."""
    model.eval()
    preds = []
    with torch.no_grad():
        for start in range(0, len(examples), batch_size):
            batch = train_v2.collate(examples[start:start + batch_size])
            preds.extend(train_v2.forward_batch(model, batch).tolist())
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

    gen = torch.Generator().manual_seed(V2_SEED)
    train_i, val_i = train_v2.split_indices(len(examples), V2_VAL_FRAC, gen)
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
        out = train_v2.forward_batch(model, batch)
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


def _fmt(x, nd=4):
    return f"{x:.{nd}f}"


def write_report(path, v2_result, v1_result, proof_rows):
    """Write the committed validation report to `path` (docs/nnue-v2-validation.md).

    Sections: (a) v2 learning curves + verdict, (b) honest v1-vs-v2 comparison,
    (c) board-size-independence proof, (d) honest-framing block.
    """
    non12 = [(r["rows"], r["cols"]) for r in proof_rows
             if (r["rows"], r["cols"]) != (12, 12)]
    non12_str = ", ".join(f"{r}x{c}" for r, c in non12)

    L = []
    L.append("# NNUE v2 Validation and Board-Size Independence Proof")
    L.append("")
    L.append("Generated by `python/v2/validate_v2.py` (deterministic, seed=0). "
             "This validates REPRESENTATION + PLUMBING + board-size generality — "
             "NOT competitive strength (see honest framing at the bottom).")
    L.append("")

    # (a) v2 learning curves
    L.append("## 1. v2 learning curves")
    L.append("")
    L.append(f"- Dictionary size (patterns): {v2_result['dict_size']}")
    L.append(f"- Dataset size (examples): {v2_result['dataset_size']}")
    L.append(f"- Config: W={V2_W}, epochs={V2_EPOCHS}, batch={V2_BATCH}, "
             f"lr={V2_LR}, seed={V2_SEED}, val_frac={V2_VAL_FRAC}")
    L.append("- Label space: WDL in {0, 0.5, 1}; loss = MSE")
    L.append("")
    L.append("| epoch | train MSE | val MSE |")
    L.append("|------:|----------:|--------:|")
    for e, tr, va in v2_result["curve"]:
        L.append(f"| {e} | {_fmt(tr)} | {_fmt(va)} |")
    L.append("")
    L.append(f"- Final train MSE: {_fmt(v2_result['final_train_mse'])}")
    L.append(f"- Final val MSE: {_fmt(v2_result['final_val_mse'])}")
    L.append(f"- Constant-predictor val MSE floor (predict mean train WDL): "
             f"{_fmt(v2_result['const_floor'])}")
    L.append(f"- Directional accuracy on val split ((pred>0.5) vs (wdl>0.5)): "
             f"{_fmt(v2_result['dir_acc'], 3)}")
    L.append("")
    beats_floor = v2_result["final_val_mse"] < v2_result["const_floor"]
    verdict = ("LEARNS SIGNAL — final val MSE is below the constant-predictor "
               "floor, i.e. the representation captures signal a mean-predictor "
               "cannot." if beats_floor else
               "DOES NOT beat the constant-predictor floor on val — the "
               "representation is not yet extracting usable signal at this "
               "data/size.")
    L.append(f"**VERDICT:** {verdict}")
    L.append("")

    # (b) v1-vs-v2 comparison
    L.append("## 2. v1 vs v2 (honest, apples-to-oranges)")
    L.append("")
    L.append("**Caveats — these numbers are NOT directly comparable:**")
    L.append("")
    L.append("- Different feature spaces: v1 uses 864 one-hot inputs; v2 uses "
             "sparse dictionary patterns + dense channel.")
    L.append("- Different label spaces: v1 regresses a CONTINUOUS target in "
             "[-1, 1]; v2 regresses WDL in {0, 0.5, 1}.")
    L.append("- MSE lives in different label spaces and is therefore NOT "
             "directly comparable across v1 and v2.")
    L.append("- Directional accuracy is given as the closest common-ground "
             "metric (does the sign / side agree with the label). No overclaim "
             "is made from the MSE columns.")
    L.append("")
    if v1_result.get("available"):
        L.append("| model | val MSE | train MSE | const floor | dir. acc |")
        L.append("|:------|--------:|----------:|------------:|---------:|")
        L.append(f"| v1 (864 one-hot, continuous target) | "
                 f"{_fmt(v1_result['best_val_mse'])} | "
                 f"{_fmt(v1_result['train_mse'])} | "
                 f"{_fmt(v1_result['const_floor'])} | "
                 f"{_fmt(v1_result['dir_acc'], 3)} |")
        L.append(f"| v2 (sparse patterns, WDL target) | "
                 f"{_fmt(v2_result['final_val_mse'])} | "
                 f"{_fmt(v2_result['final_train_mse'])} | "
                 f"{_fmt(v2_result['const_floor'])} | "
                 f"{_fmt(v2_result['dir_acc'], 3)} |")
        L.append("")
        L.append("Read only the directional-accuracy column across rows; the "
                 "MSE columns are within-model diagnostics against each model's "
                 "own constant-predictor floor.")
    else:
        L.append(f"v1 baseline unavailable: {v1_result.get('reason', 'unknown')}. "
                 "(dataset.json is gitignored; regenerate to populate this "
                 "section.)")
    L.append("")

    # (c) board-size independence
    L.append("## 3. Board-size independence proof")
    L.append("")
    L.append("The SAME trained model and SAME 12x12-mined dictionary evaluate "
             "boards of arbitrary size with NO code changes and NO 12x12 "
             "assumptions.")
    L.append("")
    L.append("| rows x cols | STM matched | NSTM matched | eval | finite |")
    L.append("|:------------|------------:|-------------:|-----:|:------:|")
    for r in proof_rows:
        L.append(f"| {r['rows']}x{r['cols']} | {r['stm_matched']} | "
                 f"{r['nstm_matched']} | {_fmt(r['eval'], 6)} | "
                 f"{'yes' if r['finite'] else 'NO'} |")
    L.append("")
    L.append("**Why this works structurally:**")
    L.append("")
    L.append("- The sparse accumulator sums per-pattern embeddings; it has no "
             "board-size dimension, so it is size-agnostic by construction.")
    L.append("- The distance bucket in the pattern contract is derived from the "
             "enemy-base Manhattan distance, which normalizes by board size — "
             "not a fixed 12x12 grid position.")
    L.append("- Small boards match FEW dictionary patterns (the dictionary was "
             "mined from 12x12 games), yet still evaluate to a finite scalar via "
             "the dense channel plus zeroed accumulators. The low match counts "
             "are honest evidence that the pipeline degrades gracefully rather "
             "than crashing or asserting a fixed size.")
    L.append("")
    L.append(f"**PROVEN across sizes {non12_str} with no code changes** "
             "(12x12 included as a sanity anchor). Every eval above is finite "
             "and scalar-shaped.")
    L.append("")

    # (d) honest framing
    L.append("## 4. Honest framing (read this)")
    L.append("")
    L.append("- The corpus is 1048 examples whose WDL labels come from the "
             "SIGN of v1's continuous target — there are no real draws and the "
             "dataset is tiny.")
    L.append("- This report validates the v2 REPRESENTATION, the training/eval "
             "PLUMBING, and BOARD-SIZE GENERALITY.")
    L.append("- It does NOT and MUST NOT claim the bot is competitively "
             "stronger. Competitive strength is DEFERRED to the larger "
             "real-outcome multi-size corpus (bead nnue-trainer-d4a.3.4) and an "
             "in-engine gauntlet.")
    L.append("")

    with open(path, "w") as f:
        f.write("\n".join(L) + "\n")
    return path


def main(argv=None):
    """Run v2 training, v1 baseline, and the board-size proof; write the report."""
    report_path = os.path.join(_REPO_ROOT, "docs", "nnue-v2-validation.md")

    print("Training v2 model (real config, seed=0)...")
    v2_result = run_v2_training()
    print(f"  final val MSE={_fmt(v2_result['final_val_mse'])} "
          f"const floor={_fmt(v2_result['const_floor'])} "
          f"dir_acc={_fmt(v2_result['dir_acc'], 3)}")

    print("Training v1 baseline (seed=0)...")
    v1_result = run_v1_baseline()
    if v1_result.get("available"):
        print(f"  v1 val MSE={_fmt(v1_result['best_val_mse'])} "
              f"dir_acc={_fmt(v1_result['dir_acc'], 3)}")
    else:
        print(f"  {v1_result.get('reason')}")

    dict_path = train_v2._DEFAULT_DICT
    pattern_to_id = extract_examples.load_dictionary(dict_path)
    print("Board-size independence proof:")
    proof_rows = board_size_proof(v2_result["model"], pattern_to_id)
    for r in proof_rows:
        print(f"  {r['rows']}x{r['cols']}: eval={_fmt(r['eval'], 6)} "
              f"stm_matched={r['stm_matched']} nstm_matched={r['nstm_matched']} "
              f"finite={r['finite']}")

    write_report(report_path, v2_result, v1_result, proof_rows)
    print(f"Wrote report: {report_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
