#!/usr/bin/env python
"""Phase 2 joint policy+value trainer (plan docs/plans/20260807-mcts-az-feasibility.md, D2).

Consumes SelfPlayMcts JSONL rows and trains the D2 net with both heads:
  policy  CE against the MCTS root visit distribution (soft targets over the legal set "pi")
  value   MSE against the game outcome z, flipped from the recorded ABSOLUTE frame into each
          row's mover frame (z_mover = z if mover == 1 else -z — the v3 mover-flip lesson;
          the features are mover-relative, so the target must be too)

Exports the exact JSON artifact PolicyNetPrior loads, plus a "value_head" section
(GAP -> fc1 relu -> fc2 -> tanh), and a Java parity fixture with per-sample head outputs
including "value". Holdout is split by game; prints holdout policy top-1 (argmax logits vs
argmax visits) and value MAE.

Usage:
  .venv/bin/python python/mcts/train_selfplay.py SELFPLAY.jsonl \
      --out candidate.json --fixture src/test/resources/mcts/mcts_value_parity.json --epochs 8
"""

import argparse
import hashlib
import json
import sys

import torch
import torch.nn as nn
import torch.nn.functional as F

import train_policy as tp

BOARD = tp.BOARD
CELLS = tp.CELLS
FLAT = tp.FLAT


class PolicyValueNet(tp.PolicyNet):
    def __init__(self, channels=32, layers=4, vhidden=32):
        super().__init__(channels, layers)
        self.value_fc1 = nn.Linear(channels, vhidden)
        self.value_fc2 = nn.Linear(vhidden, 1)

    def forward(self, x):
        for c in self.convs:
            x = F.relu(c(x))
        m = self.move_head(x).flatten(1)  # [B,144]
        u = self.pair_head(x).flatten(1)  # [B,144]
        g = x.mean(dim=(2, 3))  # global average pool [B,channels]
        v = torch.tanh(self.value_fc2(F.relu(self.value_fc1(g)))).squeeze(1)  # [B]
        return m, u, v


def mover_z(z_abs, mover):
    """Absolute-frame outcome -> the row's mover frame. THE flip point; pinned by tests."""
    return z_abs if mover == 1 else -z_abs


def tensors(rows, device):
    """Compact per-row tensors. Policy targets are (pi, pv) padded to the max legal count;
    padding entries carry weight 0 so they contribute nothing to the CE."""
    n = len(rows)
    k = max(len(r["pi"]) for r in rows)
    sym = torch.zeros((n, CELLS), dtype=torch.int8)
    ml = torch.zeros(n, dtype=torch.int8)
    nuo = torch.zeros(n, dtype=torch.int8)
    nux = torch.zeros(n, dtype=torch.int8)
    idx = torch.zeros((n, k), dtype=torch.long)
    w = torch.zeros((n, k), dtype=torch.float)
    valid = torch.zeros((n, k), dtype=torch.bool)  # real entries vs padding
    zt = torch.zeros(n, dtype=torch.float)
    for i, r in enumerate(rows):
        sym[i] = torch.tensor(r["sym"], dtype=torch.int8)
        ml[i] = r["ml"]
        nuo[i] = r["nuo"]
        nux[i] = r["nux"]
        pi = r["pi"]
        pv = torch.tensor(r["pv"], dtype=torch.float)
        idx[i, : len(pi)] = torch.tensor(pi, dtype=torch.long)
        valid[i, : len(pi)] = True
        total = pv.sum()
        if total > 0:
            w[i, : len(pi)] = pv / total
        zt[i] = mover_z(r["z"], r["mover"])
    return [t.to(device) for t in (sym, ml, nuo, nux, idx, w, valid, zt)]


def forward_batch(model, batch):
    sym, ml, nuo, nux, idx, w, valid, zt = batch
    m, u, v = model(tp.planes(sym, ml, nuo, nux))
    logits = tp.flat_logits(m, u, model.pair_bias)
    # The legal set is exactly the (non-pad) pi indices — the mask the softmax runs over.
    mask = torch.zeros_like(logits, dtype=torch.bool)
    mask.scatter_(1, idx, valid)
    logits = logits.masked_fill(~mask, -1e9)
    return logits, idx, w, v, zt


def losses(model, batch, value_weight):
    logits, idx, w, v, zt = forward_batch(model, batch)
    logp = F.log_softmax(logits, dim=1)
    policy = -(w * logp.gather(1, idx)).sum(dim=1).mean()
    value = F.mse_loss(v, zt)
    return policy + value_weight * value, policy, value


def evaluate(model, batches):
    """Holdout policy top-1 (argmax logits == argmax visit target) and value MAE."""
    model.eval()
    correct, total, mae_sum = 0, 0, 0.0
    with torch.no_grad():
        for batch in batches:
            logits, idx, w, v, zt = forward_batch(model, batch)
            target = idx.gather(1, w.argmax(dim=1, keepdim=True)).squeeze(1)
            correct += (logits.argmax(dim=1) == target).sum().item()
            mae_sum += (v - zt).abs().sum().item()
            total += zt.shape[0]
    model.train()
    return correct / total, mae_sum / total


def export_weights(model, path):
    def tolist(t):
        return t.detach().cpu().double().numpy().tolist()

    out = {
        "meta": {
            "arch": "conv-policy-value-v1",
            "board": BOARD,
            "planes": 13,
            "channels": model.convs[0].out_channels,
            "layers": len(model.convs),
        },
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
        "value_head": {
            "fc1_w": tolist(model.value_fc1.weight),  # [hidden][channels]
            "fc1_b": tolist(model.value_fc1.bias),
            "fc2_w": tolist(model.value_fc2.weight)[0],  # [hidden]
            "fc2_b": float(model.value_fc2.bias.detach()[0]),
        },
    }
    with open(path, "w") as f:
        json.dump(out, f)
    print(f"weights -> {path}")


def export_fixture(model, rows, path, k=3):
    """Java parity fixture, PolicyNetPriorParityTest discipline, plus the value output."""
    model.eval()
    samples = []
    for r in rows[:k]:
        sym = torch.tensor([r["sym"]], dtype=torch.int8)
        ml = torch.tensor([r["ml"]], dtype=torch.int8)
        nuo = torch.tensor([r["nuo"]], dtype=torch.int8)
        nux = torch.tensor([r["nux"]], dtype=torch.int8)
        with torch.no_grad():
            m, u, v = model(tp.planes(sym, ml, nuo, nux).cpu())
        samples.append(
            {
                "sym": r["sym"],
                "ml": r["ml"],
                "nuo": r["nuo"],
                "nux": r["nux"],
                "move_logits": m[0].double().numpy().tolist(),
                "pair_u": u[0].double().numpy().tolist(),
                "value": float(v[0]),
            }
        )
    with open(path, "w") as f:
        json.dump({"pair_bias": float(model.pair_bias.detach()), "samples": samples}, f)
    print(f"fixture -> {path}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("datasets", nargs="+", help="one or more SelfPlayMcts JSONL files")
    ap.add_argument("--out", default="mcts_selfplay_net.json")
    ap.add_argument("--fixture", default=None)
    ap.add_argument("--epochs", type=int, default=8)
    ap.add_argument("--batch", type=int, default=256)
    ap.add_argument("--lr", type=float, default=1e-3)
    ap.add_argument("--weight-decay", type=float, default=1e-4)
    ap.add_argument("--value-weight", type=float, default=1.0)
    ap.add_argument("--channels", type=int, default=32)
    ap.add_argument("--layers", type=int, default=4)
    ap.add_argument("--seed", type=int, default=7)
    args = ap.parse_args()

    torch.manual_seed(args.seed)
    device = "mps" if torch.backends.mps.is_available() else "cpu"
    rows = []
    for path in args.datasets:
        rows.extend(tp.load_rows(path))

    # Game-split holdout (positions inside a game share features AND the outcome label).
    def holdout(game_id):
        return int(hashlib.sha1(game_id.encode()).hexdigest(), 16) % 10 == 0

    train_rows = [r for r in rows if not holdout(r["g"])]
    val_rows = [r for r in rows if holdout(r["g"])]
    if not val_rows:  # tiny smoke datasets: hold out the last game instead of nothing
        last = rows[-1]["g"]
        train_rows = [r for r in rows if r["g"] != last]
        val_rows = [r for r in rows if r["g"] == last]
    games = len({r["g"] for r in rows})
    print(
        f"rows: {len(rows)} from {games} games (train {len(train_rows)}, holdout {len(val_rows)}), "
        f"device: {device}"
    )

    train_t = tensors(train_rows, device)
    val_batches = tp.batches_of(tensors(val_rows, device), args.batch)
    model = PolicyValueNet(args.channels, args.layers).to(device)
    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=args.weight_decay)

    n = train_t[0].shape[0]
    for epoch in range(args.epochs):
        perm = torch.randperm(n, device=device)
        shuffled = [t[perm] for t in train_t]
        pol_sum, val_sum = 0.0, 0.0
        for batch in tp.batches_of(shuffled, args.batch):
            loss, pol, val = losses(model, batch, args.value_weight)
            opt.zero_grad()
            loss.backward()
            opt.step()
            b = batch[0].shape[0]
            pol_sum += pol.item() * b
            val_sum += val.item() * b
        top1, mae = evaluate(model, val_batches)
        print(
            f"epoch {epoch + 1}/{args.epochs}: train policy {pol_sum / n:.4f}, "
            f"value {val_sum / n:.4f} | holdout top-1 {100 * top1:.1f}%, value MAE {mae:.3f}"
        )

    model = model.cpu()
    export_weights(model, args.out)
    if args.fixture:
        export_fixture(model, val_rows or rows, args.fixture)


if __name__ == "__main__":
    sys.exit(main())
