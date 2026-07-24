#!/usr/bin/env python3
"""Report honest v2 metrics on a trained model: val MSE, directional accuracy,
constant-predictor floor, and win/draw/loss class balance.

Reuses train_v2's split/collate so the val split matches training exactly.
Run AFTER train_v2.py (loads the saved .pt); does not retrain.
"""
import argparse
import json
import os

import torch

import train_v2


def class_balance(wdls):
    n = len(wdls)
    win = sum(1 for w in wdls if w > 0.75)
    loss = sum(1 for w in wdls if w < 0.25)
    draw = n - win - loss
    return win, draw, loss, n


def main(argv=None):
    p = argparse.ArgumentParser()
    p.add_argument("--examples", default=train_v2._DEFAULT_EXAMPLES)
    p.add_argument("--dict", dest="dict_path", default=train_v2._DEFAULT_DICT)
    p.add_argument("--model", default=os.path.join(train_v2._HERE, "nnue_v2_model.pt"))
    p.add_argument("--width", type=int, default=1024)
    p.add_argument("--seed", type=int, default=0)
    p.add_argument("--val-frac", type=float, default=0.2)
    args = p.parse_args(argv)

    num_patterns = train_v2.read_num_patterns(args.dict_path)
    examples = train_v2.load_examples(args.examples, dict_path=args.dict_path)

    model = train_v2.NNUEv2(num_patterns, W=args.width)
    model.load_state_dict(torch.load(args.model))
    model.eval()

    gen = torch.Generator().manual_seed(args.seed)
    train_i, val_i = train_v2.split_indices(len(examples), args.val_frac, gen)
    train_wdl = [examples[i]["wdl"] for i in train_i]
    val_wdl = [examples[i]["wdl"] for i in val_i]
    val_ex = [examples[i] for i in val_i]

    # predictions in val order
    preds = []
    with torch.no_grad():
        for s in range(0, len(val_ex), 1024):
            b = train_v2.collate(val_ex[s:s + 1024])
            preds.extend(train_v2.forward_batch(model, b).tolist())

    val_mse = sum((p - w) ** 2 for p, w in zip(preds, val_wdl)) / len(val_wdl)
    mean_train = sum(train_wdl) / len(train_wdl)
    const_floor = sum((w - mean_train) ** 2 for w in val_wdl) / len(val_wdl)
    # directional accuracy over non-draw val examples (a draw has no direction)
    dec = [(p, w) for p, w in zip(preds, val_wdl) if w > 0.75 or w < 0.25]
    dir_acc = (sum((p > 0.5) == (w > 0.5) for p, w in dec) / len(dec)) if dec else float("nan")

    w_all, d_all, l_all, n_all = class_balance([e["wdl"] for e in examples])
    w_v, d_v, l_v, n_v = class_balance(val_wdl)

    out = {
        "num_examples": n_all,
        "val_size": n_v,
        "val_mse": round(val_mse, 6),
        "const_floor": round(const_floor, 6),
        "beats_floor": val_mse < const_floor,
        "dir_acc_decisive": round(dir_acc, 4),
        "mean_train_wdl": round(mean_train, 4),
        "class_balance_all_pct": {
            "win": round(100 * w_all / n_all, 1),
            "draw": round(100 * d_all / n_all, 1),
            "loss": round(100 * l_all / n_all, 1),
        },
        "class_balance_val_pct": {
            "win": round(100 * w_v / n_v, 1),
            "draw": round(100 * d_v / n_v, 1),
            "loss": round(100 * l_v / n_v, 1),
        },
    }
    print(json.dumps(out, indent=2))


if __name__ == "__main__":
    main()
