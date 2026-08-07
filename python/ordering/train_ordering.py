#!/usr/bin/env python
"""Sparse linear move-ordering table (epic nnue-trainer-1jh): the NNUE trick applied to ordering.

score(cell c) = sum over the position's 145 active features f of W[f][c], where the features are
the NNUE v3 set (V3FeatureMiner.idx: (r*12+c)*8 + PatternContract symbol, 1152 slots) plus the
4 tempo slots (id 1152+movesLeft) — cheap enough (~145*144 adds) for every interior search node.

Two variants over the MctsPolicyDatasetEmitter rows:
  distilled  soft-target CE against the conv policy net's (mcts_policy.json) masked move
             distribution — the teacher this table is meant to approximate,
  direct     hard CE against the played move.
Both are reported on a by-game holdout; the better top-1 (vs played move) is exported.

Usage:
  .venv/bin/python python/ordering/train_ordering.py DATASET.jsonl \
      --teacher mcts_policy.json --out ordering_policy.json \
      --fixture src/test/resources/ordering/ordering_parity.json
"""

import argparse
import hashlib
import json
import os
import sys

import torch
import torch.nn.functional as F

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "mcts"))
from train_policy import PolicyNet, planes  # noqa: E402  (shared encoding, single source)

BOARD = 12
CELLS = BOARD * BOARD
STATES = 8
TEMPO = 4
FEATURES = CELLS * STATES + TEMPO  # 1156


def load_rows(path):
    with open(path) as f:
        return [json.loads(line) for line in f]


def load_teacher(path):
    """mcts_policy.json -> PolicyNet with the exported weights (same file PolicyNetPrior loads)."""
    with open(path) as f:
        art = json.load(f)
    meta = art["meta"]
    net = PolicyNet(channels=meta["channels"], layers=meta["layers"])
    for k, conv in enumerate(art["conv"]):
        net.convs[k].weight.data = torch.tensor(conv["w"], dtype=torch.float32)
        net.convs[k].bias.data = torch.tensor(conv["b"], dtype=torch.float32)
    net.move_head.weight.data = torch.tensor(art["move_head"]["w"], dtype=torch.float32).reshape(
        1, meta["channels"], 1, 1
    )
    net.move_head.bias.data = torch.tensor([art["move_head"]["b"]], dtype=torch.float32)
    net.pair_head.weight.data = torch.tensor(art["pair_head"]["w"], dtype=torch.float32).reshape(
        1, meta["channels"], 1, 1
    )
    net.pair_head.bias.data = torch.tensor([art["pair_head"]["b"]], dtype=torch.float32)
    net.eval()
    return net


def tensors(rows, device):
    n = len(rows)
    active = torch.zeros((n, CELLS + 1), dtype=torch.long)  # 144 positional + 1 tempo id
    move_mask = torch.zeros((n, CELLS), dtype=torch.bool)
    label = torch.full((n,), -1, dtype=torch.long)  # played cell; -1 for neutral-pair rows
    sym = torch.zeros((n, CELLS), dtype=torch.int8)
    ml = torch.zeros(n, dtype=torch.int8)
    nuo = torch.zeros(n, dtype=torch.int8)
    nux = torch.zeros(n, dtype=torch.int8)
    for k, r in enumerate(rows):
        s = torch.tensor(r["sym"], dtype=torch.long)
        active[k, :CELLS] = torch.arange(CELLS) * STATES + s
        active[k, CELLS] = CELLS * STATES + r["ml"]
        move_mask[k, r["lm"]] = True
        if r["t"] == "m":
            label[k] = r["a"]
        sym[k] = s.to(torch.int8)
        ml[k] = r["ml"]
        nuo[k] = r["nuo"]
        nux[k] = r["nux"]
    return [t.to(device) for t in (active, move_mask, label, sym, ml, nuo, nux)]


def teacher_move_logits(net, sym, ml, nuo, nux, device, batch=1024):
    """Teacher move-head logits [N,144], computed once up front."""
    out = []
    net = net.to(device)
    with torch.no_grad():
        for i in range(0, sym.shape[0], batch):
            m, _ = net(planes(sym[i : i + batch], ml[i : i + batch], nuo[i : i + batch], nux[i : i + batch]))
            out.append(m)
    return torch.cat(out).to(device)


def student_logits(w, active):
    return F.embedding(active, w).sum(dim=1)  # [B,144]


def metrics(w, active, move_mask, label):
    """Holdout top-1/top-3/mean-rank of the PLAYED move among legal moves (move rows only)."""
    keep = label >= 0
    active, move_mask, label = active[keep], move_mask[keep], label[keep]
    with torch.no_grad():
        logits = student_logits(w, active).masked_fill(~move_mask, -1e9)
        played = logits.gather(1, label[:, None])
        rank = (logits > played).sum(dim=1) + 1  # 1-based, strict-better count
        top1 = (rank == 1).float().mean().item()
        top3 = (rank <= 3).float().mean().item()
        return top1, top3, rank.float().mean().item()


def train(mode, tensors_list, teacher, epochs, batch, lr, device):
    active, move_mask, label = tensors_list[:3]
    w = torch.zeros((FEATURES, CELLS), device=device, requires_grad=True)
    opt = torch.optim.Adam([w], lr=lr)
    n = active.shape[0]
    if mode == "direct":
        keep = label >= 0
        active, move_mask, label = active[keep], move_mask[keep], label[keep]
        teacher = None
        n = active.shape[0]
    for _ in range(epochs):
        perm = torch.randperm(n, device=device)
        for i in range(0, n, batch):
            idx = perm[i : i + batch]
            logits = student_logits(w, active[idx]).masked_fill(~move_mask[idx], -1e9)
            if mode == "direct":
                loss = F.cross_entropy(logits, label[idx])
            else:
                t = teacher[idx].masked_fill(~move_mask[idx], -1e9)
                loss = -(F.softmax(t, dim=1) * F.log_softmax(logits, dim=1)).sum(dim=1).mean()
            opt.zero_grad()
            loss.backward()
            opt.step()
    return w.detach()


def export(w, mode, out_path, fixture_path, fixture_rows):
    # Round once, compute the fixture from the ROUNDED table: java parity is then exact-ish (1e-9).
    wr = [[round(float(v), 6) for v in row] for row in w.cpu().tolist()]
    art = {
        "meta": {
            "arch": "ordering-sparse-linear-v1",
            "board": BOARD,
            "features": FEATURES,
            "cells": CELLS,
            "tempo_slots": TEMPO,
            "trained": mode,
        },
        "w": wr,
    }
    with open(out_path, "w") as f:
        json.dump(art, f)
    print(f"weights ({mode}) -> {out_path}")

    if fixture_path:
        samples = []
        for r in fixture_rows:
            scores = [0.0] * CELLS
            for i in range(CELLS):
                fid = i * STATES + r["sym"][i]
                for c in range(CELLS):
                    scores[c] += wr[fid][c]
            fid = CELLS * STATES + r["ml"]
            for c in range(CELLS):
                scores[c] += wr[fid][c]
            samples.append({"sym": r["sym"], "ml": r["ml"], "scores": scores})
        os.makedirs(os.path.dirname(fixture_path), exist_ok=True)
        with open(fixture_path, "w") as f:
            json.dump({"samples": samples}, f)
        print(f"fixture -> {fixture_path}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("dataset")
    ap.add_argument("--teacher", default="mcts_policy.json")
    ap.add_argument("--out", default="ordering_policy.json")
    ap.add_argument("--fixture", default=None)
    ap.add_argument("--epochs", type=int, default=12)
    ap.add_argument("--batch", type=int, default=1024)
    ap.add_argument("--lr", type=float, default=0.02)
    ap.add_argument("--seed", type=int, default=7)
    args = ap.parse_args()

    torch.manual_seed(args.seed)
    device = "mps" if torch.backends.mps.is_available() else "cpu"
    rows = load_rows(args.dataset)
    rows = [r for r in rows if len(r["lm"]) >= 2]  # nothing to order otherwise

    def holdout(game_id):  # by-game split, same hash as train_policy (v3 discipline)
        return int(hashlib.sha1(game_id.encode()).hexdigest(), 16) % 10 == 0

    train_rows = [r for r in rows if not holdout(r["g"])]
    val_rows = [r for r in rows if holdout(r["g"])]
    print(f"rows: {len(rows)} (train {len(train_rows)}, holdout {len(val_rows)}), device: {device}")

    train_t = tensors(train_rows, device)
    val_t = tensors(val_rows, device)

    teacher_net = load_teacher(args.teacher)
    t_logits = teacher_move_logits(teacher_net, *train_t[3:], device=device)

    results = {}
    for mode in ("distilled", "direct"):
        w = train(mode, train_t, t_logits, args.epochs, args.batch, args.lr, device)
        top1, top3, mean_rank = metrics(w, *val_t[:3])
        results[mode] = (w, top1, top3, mean_rank)
        print(f"{mode:9s} holdout vs played move: top-1 {100*top1:.1f}%, top-3 {100*top3:.1f}%, mean rank {mean_rank:.2f}")

    # Teacher's own holdout numbers, as the ceiling this table is chasing.
    vt = teacher_move_logits(teacher_net, *val_t[3:], device=device)
    keep = val_t[2] >= 0
    tl = vt[keep].masked_fill(~val_t[1][keep], -1e9)
    played = tl.gather(1, val_t[2][keep][:, None])
    trank = (tl > played).sum(dim=1) + 1
    print(
        f"teacher   holdout vs played move: top-1 {100*(trank==1).float().mean():.1f}%, "
        f"top-3 {100*(trank<=3).float().mean():.1f}%, mean rank {trank.float().mean():.2f}"
    )

    best = max(results, key=lambda m: results[m][1])
    print(f"exporting: {best}")
    export(results[best][0], best, args.out, args.fixture, val_rows[:3] if val_rows else rows[:3])


if __name__ == "__main__":
    sys.exit(main())
