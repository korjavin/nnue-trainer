#!/usr/bin/env python
"""Phase 1 supervised policy prior (plan docs/plans/20260807-mcts-az-feasibility.md, D2/D4).

Trains the plan's policy net on the (state, chosen-action) rows emitted by
MctsPolicyDatasetEmitter and exports weights + a Java parity fixture.

Architecture (D2, policy-only):
  input  13 planes 12x12: 8 one-hot cell symbols (PatternContract.getSymbol, mover-relative),
         movesLeft one-hot (3), own neutralUsed, opp neutralUsed
  trunk  4x conv3x3, 32 channels, ReLU, padding 1
  heads  1x1 conv -> 144 move logits; 1x1 conv -> 144 per-cell pair utilities u
         pair logit(i,j) = u[i] + u[j] + b_pair  (factored: 54 neutral rows in the corpus,
         far below the plan's 500 threshold, so a near-uniform pair head is expected/accepted)
  softmax over the legal actions only.

Usage:
  .venv/bin/python python/mcts/train_policy.py DATASET.jsonl \
      --out mcts_policy.json --fixture src/test/resources/.../mcts_policy_parity.json \
      --epochs 8
"""

import argparse
import hashlib
import json
import sys

import torch
import torch.nn as nn
import torch.nn.functional as F

BOARD = 12
CELLS = BOARD * BOARD
PAIR_OFFSET = CELLS  # flat action space: [0,144) moves, 144 + i*144 + j pairs (i<j)
FLAT = CELLS + CELLS * CELLS


class PolicyNet(nn.Module):
    def __init__(self, channels=32, layers=4):
        super().__init__()
        convs = [nn.Conv2d(13, channels, 3, padding=1)]
        for _ in range(layers - 1):
            convs.append(nn.Conv2d(channels, channels, 3, padding=1))
        self.convs = nn.ModuleList(convs)
        self.move_head = nn.Conv2d(channels, 1, 1)
        self.pair_head = nn.Conv2d(channels, 1, 1)
        self.pair_bias = nn.Parameter(torch.zeros(()))

    def forward(self, x):
        for c in self.convs:
            x = F.relu(c(x))
        m = self.move_head(x).flatten(1)  # [B,144]
        u = self.pair_head(x).flatten(1)  # [B,144]
        return m, u


def planes(sym, ml, nuo, nux):
    """[B,13,12,12] float planes from compact per-sample tensors."""
    b = sym.shape[0]
    one_hot = F.one_hot(sym.long(), 8).float()  # [B,144,8]
    x = one_hot.permute(0, 2, 1).reshape(b, 8, BOARD, BOARD)
    ml_oh = F.one_hot(ml.long() - 1, 3).float()  # [B,3]
    ml_planes = ml_oh[:, :, None, None].expand(b, 3, BOARD, BOARD)
    nuo_p = nuo.float()[:, None, None, None].expand(b, 1, BOARD, BOARD)
    nux_p = nux.float()[:, None, None, None].expand(b, 1, BOARD, BOARD)
    return torch.cat([x, ml_planes, nuo_p, nux_p], dim=1)


def flat_logits(m, u, pair_bias):
    """[B, FLAT]: move logits then u[i]+u[j]+b for every ordered (i,j) cell pair."""
    pair = u[:, :, None] + u[:, None, :] + pair_bias  # [B,144,144]
    return torch.cat([m, pair.flatten(1)], dim=1)


def load_rows(path):
    rows = []
    with open(path) as f:
        for line in f:
            rows.append(json.loads(line))
    return rows


def tensors(rows, device):
    n = len(rows)
    sym = torch.zeros((n, CELLS), dtype=torch.int8)
    ml = torch.zeros(n, dtype=torch.int8)
    nuo = torch.zeros(n, dtype=torch.int8)
    nux = torch.zeros(n, dtype=torch.int8)
    move_mask = torch.zeros((n, CELLS), dtype=torch.bool)
    own_mask = torch.zeros((n, CELLS), dtype=torch.bool)
    label = torch.zeros(n, dtype=torch.long)
    for k, r in enumerate(rows):
        sym[k] = torch.tensor(r["sym"], dtype=torch.int8)
        ml[k] = r["ml"]
        nuo[k] = r["nuo"]
        nux[k] = r["nux"]
        move_mask[k, r["lm"]] = True
        if r["oc"]:
            own_mask[k, r["oc"]] = True
        if r["t"] == "m":
            label[k] = r["a"]
        else:
            i, j = r["a"]
            label[k] = PAIR_OFFSET + i * CELLS + j
    return [t.to(device) for t in (sym, ml, nuo, nux, move_mask, own_mask, label)]


def action_mask(move_mask, own_mask):
    """[B, FLAT] legality mask: legal moves, plus i<j pairs of owned cells."""
    b = move_mask.shape[0]
    pair = own_mask[:, :, None] & own_mask[:, None, :]  # [B,144,144]
    triu = torch.triu(torch.ones(CELLS, CELLS, dtype=torch.bool, device=move_mask.device), 1)
    pair &= triu
    return torch.cat([move_mask, pair.flatten(1)], dim=1)


def masked_ce(model, batch):
    sym, ml, nuo, nux, move_mask, own_mask, label = batch
    m, u = model(planes(sym, ml, nuo, nux))
    logits = flat_logits(m, u, model.pair_bias)
    mask = action_mask(move_mask, own_mask)
    logits = logits.masked_fill(~mask, -1e9)
    return logits, label


def evaluate(model, batches):
    model.eval()
    correct, total, loss_sum = 0, 0, 0.0
    with torch.no_grad():
        for batch in batches:
            logits, label = masked_ce(model, batch)
            loss_sum += F.cross_entropy(logits, label, reduction="sum").item()
            correct += (logits.argmax(dim=1) == label).sum().item()
            total += label.shape[0]
    model.train()
    return correct / total, loss_sum / total


def batches_of(tensors_list, batch_size):
    n = tensors_list[0].shape[0]
    return [
        tuple(t[i : i + batch_size] for t in tensors_list) for i in range(0, n, batch_size)
    ]


def export_weights(model, path):
    def tolist(t):
        return t.detach().cpu().double().numpy().tolist()

    out = {
        "meta": {
            "arch": "conv-policy-v1",
            "board": BOARD,
            "planes": 13,
            "channels": model.convs[0].out_channels,
            "layers": len(model.convs),
        },
        # conv[k].w is [out][in][3][3], conv[k].b is [out] — torch's native layout.
        "conv": [{"w": tolist(c.weight), "b": tolist(c.bias)} for c in model.convs],
        "move_head": {
            "w": tolist(model.move_head.weight)[0],
            "b": float(model.move_head.bias.detach()),
        },
        "pair_head": {
            "w": tolist(model.pair_head.weight)[0],
            "b": float(model.pair_head.bias.detach()),
        },
        "pair_bias": float(model.pair_bias.detach()),
    }
    with open(path, "w") as f:
        json.dump(out, f)
    print(f"weights -> {path}")


def export_fixture(model, rows, path, k=3):
    """Java parity fixture: k samples with the exact head outputs the loader must reproduce."""
    model.eval()
    samples = []
    for r in rows[:k]:
        sym = torch.tensor([r["sym"]], dtype=torch.int8)
        ml = torch.tensor([r["ml"]], dtype=torch.int8)
        nuo = torch.tensor([r["nuo"]], dtype=torch.int8)
        nux = torch.tensor([r["nux"]], dtype=torch.int8)
        with torch.no_grad():
            m, u = model(planes(sym, ml, nuo, nux).cpu())
        samples.append(
            {
                "sym": r["sym"],
                "ml": r["ml"],
                "nuo": r["nuo"],
                "nux": r["nux"],
                "move_logits": m[0].double().numpy().tolist(),
                "pair_u": u[0].double().numpy().tolist(),
            }
        )
    with open(path, "w") as f:
        json.dump({"pair_bias": float(model.pair_bias.detach()), "samples": samples}, f)
    print(f"fixture -> {path}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("dataset")
    ap.add_argument("--out", default="mcts_policy.json")
    ap.add_argument("--fixture", default=None)
    ap.add_argument("--epochs", type=int, default=8)
    ap.add_argument("--batch", type=int, default=512)
    ap.add_argument("--lr", type=float, default=1e-3)
    ap.add_argument("--weight-decay", type=float, default=1e-4)
    ap.add_argument("--seed", type=int, default=7)
    args = ap.parse_args()

    torch.manual_seed(args.seed)
    device = "mps" if torch.backends.mps.is_available() else "cpu"
    rows = load_rows(args.dataset)

    # Holdout split BY GAME (positions inside a game share nearly all features — v3 discipline).
    def holdout(game_id):
        return int(hashlib.sha1(game_id.encode()).hexdigest(), 16) % 10 == 0

    train_rows = [r for r in rows if not holdout(r["g"])]
    val_rows = [r for r in rows if holdout(r["g"])]
    print(
        f"rows: {len(rows)} (train {len(train_rows)}, holdout {len(val_rows)}), "
        f"neutral rows: {sum(1 for r in rows if r['t'] == 'p')}, device: {device}"
    )

    train_t = tensors(train_rows, device)
    val_batches = batches_of(tensors(val_rows, device), args.batch)
    model = PolicyNet().to(device)
    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=args.weight_decay)

    n = train_t[0].shape[0]
    for epoch in range(args.epochs):
        perm = torch.randperm(n, device=device)
        shuffled = [t[perm] for t in train_t]
        total_loss = 0.0
        for batch in batches_of(shuffled, args.batch):
            logits, label = masked_ce(model, batch)
            loss = F.cross_entropy(logits, label)
            opt.zero_grad()
            loss.backward()
            opt.step()
            total_loss += loss.item() * label.shape[0]
        top1, val_loss = evaluate(model, val_batches)
        print(
            f"epoch {epoch + 1}/{args.epochs}: train loss {total_loss / n:.4f}, "
            f"holdout loss {val_loss:.4f}, holdout top-1 {100 * top1:.1f}%"
        )

    model = model.cpu()
    export_weights(model, args.out)
    if args.fixture:
        export_fixture(model, val_rows or rows, args.fixture)


if __name__ == "__main__":
    sys.exit(main())
