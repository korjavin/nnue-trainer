"""NNUE v3 net: a 1152-H-1 ReLU net trained to ORDER SIBLING MOVES (bead nnue-trainer-1uz).

Input is the JSONL emitted by V3SiblingDatasetEmitter -- one row per legal child
of a replayed position:

    {"game_id": G, "pos_id": P, "active": [144 ids], "ht": <hand-tuned score>}

    python3 -m python.v3.train_net /tmp/nnue_v3_siblings.jsonl --sweep
    python3 -m python.v3.train_net /tmp/nnue_v3_siblings.jsonl --hidden 32 --out nnue_v3_net.json

WHY RANKING AND NOT REGRESSION.  The v3.1 linear ridge fit reached held-out
R2 = 0.976 and lost the strength gauntlet 7-17.  R2 is variance explained over
the whole position distribution, which is dominated by wide, easy differences
between unrelated positions.  Search never makes that comparison: it compares
the CHILDREN OF ONE POSITION, whose hand-tuned scores differ by a median of
~1300 while the linear model's holdout MAE was 1230.  The error was 0.9x the
difference it had to resolve, so the ordering was noise.  This trains the
decision search actually makes: a listwise softmax (ListNet) loss over each
sibling group, with an MSE term only as a UNIT ANCHOR (see --mse-weight).

x is 0/1 over 1152 features with exactly 144 active, so W1 @ x is the SUM OF
W1's COLUMNS over the active ids -- an nn.EmbeddingBag(mode="sum"), never a
dense matmul.

Targets are standardised (mu, sigma from TRAIN rows) for conditioning and the
transform is folded back into the exported w2/b2, so the shipped net emits raw
hand-tuned units with no scaling and no squashing.

Splits are three-way BY GAME: train fits, val early-stops and picks H, test is
what gets reported.  Positions inside one game share nearly all their features,
so a position-level split measures memorisation of the game.
"""
import argparse
import json
import os

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

N_FEATURES = 1152  # 12*12 cells * 8 states, idx(r,c,s) = (r*12+c)*8 + s
N_ACTIVE = 144

# Recorded by V3OrderingProbe / docs/nnue-v3-capacity.md for the shipped LINEAR model, quoted
# beside every result so the comparison is never left implicit.
#
# CAVEAT, and it matters: top1/spearman were measured over sibling groups, but mae/r2 were
# measured over the 6589 PARENT positions, a much narrower population than the children this
# trains on. The same linear model refit on sibling rows has MAE 5275-6939 (`--ridge`), so
# 1230 is NOT the MAE to beat here -- see the "MAE target was mis-derived" section of
# docs/nnue-v3-net.md. `--ridge` and `--rank-weight 0` are the like-for-like controls.
LINEAR_BASELINE = {"top1": 0.412, "spearman": 0.586, "mae": 1230.0, "r2": 0.976}


# --------------------------------------------------------------------------- data


def load_dataset(path):
    """-> dict(gidx, pos, active, ht). Accepts the emitter's .jsonl or a cached .npz.

    The .jsonl is parsed into PREALLOCATED arrays: a list-of-lists of 224k x 144 ints costs
    several GB as Python objects and gets the process OOM-killed on a normal dev box.
    """
    if path.endswith(".npz"):
        d = np.load(path, allow_pickle=True)
        return {k: d[k] for k in ("gidx", "pos", "active", "ht")}
    cache = path + ".npz"
    if os.path.exists(cache) and os.path.getmtime(cache) >= os.path.getmtime(path):
        return load_dataset(cache)
    n = sum(1 for _ in open(path))
    if n == 0:
        raise SystemExit("no rows in %s -- did V3SiblingDatasetEmitter run?" % path)
    active = np.empty((n, N_ACTIVE), dtype=np.int16)
    ht = np.empty(n, dtype=np.float64)
    pos = np.empty(n, dtype=np.int32)
    gidx = np.empty(n, dtype=np.int32)
    seen = {}
    with open(path) as f:
        for i, line in enumerate(f):
            try:
                r = json.loads(line)
                a = r["active"]
                if len(a) != N_ACTIVE:
                    raise ValueError("expected %d active ids, got %d" % (N_ACTIVE, len(a)))
                active[i] = a
                ht[i] = r["ht"]
                pos[i] = r["pos_id"]
                g = r["game_id"]
            except (ValueError, KeyError, TypeError) as exc:
                raise SystemExit("%s:%d: %s" % (path, i + 1, exc)) from exc
            gidx[i] = seen.setdefault(g, len(seen))
    # int16 holds 0..1151 but NEGATIVE ids would silently index from the end in both numpy and
    # torch, binding a weight to the wrong cell instead of raising.
    if active.min() < 0 or active.max() >= N_FEATURES:
        raise SystemExit(
            "feature id out of range in %s: [%d, %d] not within [0, %d)"
            % (path, active.min(), active.max(), N_FEATURES)
        )
    data = {"gidx": gidx, "pos": pos, "active": active, "ht": ht}
    np.savez_compressed(cache, **data)
    return data


def groups(pos):
    """-> (start[g], length[g]) over CONTIGUOUS runs of equal pos_id.

    The emitter writes a group's children consecutively; a non-contiguous file would silently
    split one sibling group into several, which is exactly the bug this whole bead is about.
    """
    if np.any(np.diff(pos) < 0):
        raise SystemExit("pos_id is not sorted -- sibling groups would be split")
    edges = np.flatnonzero(np.diff(pos)) + 1
    start = np.concatenate(([0], edges))
    length = np.diff(np.concatenate((start, [len(pos)])))
    return start, length


def split_games(gidx, seed, fracs=(0.6, 0.2, 0.2)):
    """-> three boolean row masks, split on WHOLE games."""
    games = np.unique(gidx)
    order = np.random.default_rng(seed).permutation(games)
    n_tr = int(round(fracs[0] * len(games)))
    n_va = int(round(fracs[1] * len(games)))
    parts = [order[:n_tr], order[n_tr : n_tr + n_va], order[n_tr + n_va :]]
    if min(len(p) for p in parts) == 0:
        raise SystemExit("empty split: %d games over %s" % (len(games), fracs))
    return [np.isin(gidx, p) for p in parts]


def subset(start, length, mask):
    """Group ids whose rows all fall inside `mask` (splits are by game, so groups never straddle)."""
    return np.flatnonzero(mask[start])


# --------------------------------------------------------------------------- model


class Net(nn.Module):
    """eval(x) = w2 . relu(sum_{i in active} w1[:, i] + b1) + b2, on standardised targets."""

    def __init__(self, hidden, dropout=0.0):
        super().__init__()
        self.hidden = hidden
        # weight is (features, hidden) -- w1[h][i] is emb.weight[i, h] on export.
        self.emb = nn.EmbeddingBag(N_FEATURES, max(hidden, 1), mode="sum")
        # 144 terms are summed, so std(sum) = 12 * std(w): 1/12 keeps the pre-activation O(1).
        nn.init.normal_(self.emb.weight, std=1.0 / N_ACTIVE**0.5)
        self.b1 = nn.Parameter(torch.zeros(max(hidden, 1)))
        self.drop = nn.Dropout(dropout)
        self.out = nn.Linear(max(hidden, 1), 1) if hidden > 0 else None

    def forward(self, idx):
        z = self.emb(idx) + self.b1
        if self.out is None:  # hidden == 0: the LINEAR baseline, same data and same loss
            return z.squeeze(-1)
        return self.out(self.drop(F.relu(z))).squeeze(-1)


# --------------------------------------------------------------------------- loss


def group_loss(pred, target, mask, temp, mse_weight, rank_weight=1.0):
    """ListNet cross-entropy over each sibling group + an MSE unit anchor.

    ListNet, not pairwise: the metric that matters is top-1 agreement, and a softmax over the
    group is a smooth surrogate for exactly that -- at temp ~ the median sibling gap most of the
    target mass sits on the best one or two children, so the gradient spends itself where search
    makes its decision instead of on the 30 hopeless moves.
    """
    neg = torch.finfo(pred.dtype).min
    tgt = torch.softmax(torch.where(mask, target / temp, torch.full_like(target, neg)), dim=1)
    logp = torch.log_softmax(torch.where(mask, pred / temp, torch.full_like(pred, neg)), dim=1)
    listnet = -(tgt * logp * mask).sum(dim=1).mean()
    mse = (((pred - target) ** 2) * mask).sum() / mask.sum()
    # rank_weight=0 turns this into a pure value regression -- the objective the shipped v3.1
    # linear model was fit with, kept as a runnable control rather than a quoted number.
    return rank_weight * listnet + mse_weight * mse, listnet.item(), mse.item()


# --------------------------------------------------------------------------- metrics


def rankdata(a):
    """Ranks with ties averaged (no scipy in this environment)."""
    n = len(a)
    order = np.argsort(a, kind="stable")
    s = a[order]
    r = np.empty(n, dtype=np.float64)
    i = 0
    while i < n:
        j = i
        while j + 1 < n and s[j + 1] == s[i]:
            j += 1
        r[order[i : j + 1]] = (i + j) / 2.0 + 1.0
        i = j + 1
    return r


def spearman(a, b):
    ra, rb = rankdata(a), rankdata(b)
    ra -= ra.mean()
    rb -= rb.mean()
    den = np.sqrt((ra * ra).sum() * (rb * rb).sum())
    return 0.0 if den == 0 else float((ra * rb).sum() / den)


def metrics(pred, ht, start, length, gids):
    """Ordering metrics over sibling groups, plus MAE/R2 over their rows (SECONDARY)."""
    rhos = np.empty(len(gids))
    top1 = 0
    rows = []
    within = []
    for k, g in enumerate(gids):
        sl = slice(start[g], start[g] + length[g])
        p, y = pred[sl], ht[sl]
        rhos[k] = spearman(y, p)
        # np.argmax takes the FIRST maximum, matching V3OrderingProbe's argmax.
        top1 += int(np.argmax(p) == np.argmax(y))
        rows.append(sl)
        # Residual with the group's mean error removed. Ordering is invariant to a per-group
        # offset, so THIS -- not plain MAE -- is the error that competes with the sibling gap.
        r = p - y
        within.append(np.abs(r - r.mean()))
    idx = np.concatenate([np.arange(sl.start, sl.stop) for sl in rows])
    p, y = pred[idx], ht[idx]
    ss = float(((y - y.mean()) ** 2).sum())
    return {
        "top1": top1 / len(gids),
        "spearman_mean": float(rhos.mean()),
        "spearman_median": float(np.median(rhos)),
        "mae": float(np.abs(p - y).mean()),
        "mae_within": float(np.concatenate(within).mean()),
        "r2": 1.0 - float(((y - p) ** 2).sum()) / ss if ss else 0.0,
        "groups": len(gids),
        "rows": len(idx),
    }


# --------------------------------------------------------------------------- linear baseline


def rows_of(start, length, gids):
    return np.concatenate([np.arange(start[g], start[g] + length[g]) for g in gids])


def ridge_baseline(data, start, length, tr, lam, chunk=4096):
    """Closed-form linear ridge on the sibling rows -> per-row predictions.

    This is the v3.1 model -- eval = bias + sum of the active weights -- fit the way v3.1 fit it
    (normal equations, not SGD), on THIS dataset and THIS split. It is the baseline the ordering
    numbers have to beat, and fitting it properly matters: an SGD linear model early-stopped on an
    ordering metric is a weaker straw man than the thing actually shipped.

    Neither XtX nor the predictions ever materialise a dense design matrix for the whole corpus
    (223893 x 1153 float64 is 2 GB); only one chunk at a time.
    """
    d = N_FEATURES + 1
    a = np.zeros((d, d), dtype=np.float64)
    b = np.zeros(d, dtype=np.float64)
    rows = rows_of(start, length, tr)
    y = data["ht"][rows]
    for i in range(0, len(rows), chunk):
        idx = data["active"][rows[i : i + chunk]].astype(np.int64)
        x = np.zeros((len(idx), d), dtype=np.float64)
        x[:, -1] = 1.0
        x[np.repeat(np.arange(len(idx)), N_ACTIVE), idx.ravel()] = 1.0
        a += x.T @ x
        b += x.T @ y[i : i + chunk]
    a[np.diag_indices(d)] += lam
    a[-1, -1] -= lam  # the intercept is never shrunk
    w = np.linalg.lstsq(a, b, rcond=None)[0]
    active = data["active"]
    out = np.empty(len(active), dtype=np.float64)
    for i in range(0, len(active), 20000):
        out[i : i + 20000] = w[active[i : i + 20000].astype(np.int64)].sum(axis=1) + w[-1]
    return out


# --------------------------------------------------------------------------- train


def batches(gids, start, length, batch_groups, rng):
    """Group batches, size-bucketed then shuffled.

    Groups run from 3 to 566 children; batching random groups together would pad every batch to
    the largest one in it and waste most of the compute on padding.
    """
    order = gids[np.argsort(length[gids], kind="stable")]
    chunks = [order[i : i + batch_groups] for i in range(0, len(order), batch_groups)]
    rng.shuffle(chunks)
    return chunks


def pad_batch(chunk, start, length, active, y_t):
    """Pad a batch of variable-length groups to (B, Lmax) with a validity mask.

    `active` stays int16 in numpy (64 MB); only the batch is widened to the int64 EmbeddingBag
    wants. Materialising the whole corpus as int64 is 258 MB and gets OOM-killed on a 1 GB box.
    """
    lmax = int(length[chunk].max())
    ar = np.arange(lmax)
    mask = ar[None, :] < length[chunk][:, None]
    idx = np.minimum(start[chunk][:, None] + ar[None, :], len(y_t) - 1)
    rows = torch.from_numpy(active[idx.ravel()].astype(np.int64))
    return rows, y_t[torch.from_numpy(idx)], torch.from_numpy(mask), idx.shape


def predict(model, active, chunk=8192):
    model.eval()
    out = np.empty(len(active), dtype=np.float64)
    with torch.no_grad():
        for i in range(0, len(active), chunk):
            block = torch.from_numpy(active[i : i + chunk].astype(np.int64))
            out[i : i + chunk] = model(block).numpy()
    return out


def train(data, start, length, tr, va, args, hidden, verbose=True):
    """Fit one H. Early-stops on VAL top-1 and returns the best-val state."""
    ht = data["ht"]
    active = data["active"]
    tr_rows = np.concatenate([np.arange(start[g], start[g] + length[g]) for g in tr])
    mu, sigma = float(ht[tr_rows].mean()), float(ht[tr_rows].std())
    y_t = torch.from_numpy((ht - mu) / sigma).float()

    torch.manual_seed(args.seed)
    model = Net(hidden, args.dropout)
    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=args.weight_decay)
    rng = np.random.default_rng(args.seed)
    temp = args.temp / sigma

    best = (-1.0, None, 0)
    for epoch in range(1, args.epochs + 1):
        model.train()
        tot = 0.0
        for chunk in batches(tr, start, length, args.batch_groups, rng):
            idx2d, tgt, mask, shape = pad_batch(chunk, start, length, active, y_t)
            pred = model(idx2d).reshape(shape)
            loss, _, _ = group_loss(pred, tgt, mask, temp, args.mse_weight, args.rank_weight)
            opt.zero_grad()
            loss.backward()
            opt.step()
            tot += loss.item()
        pred = predict(model, active) * sigma + mu
        m = metrics(pred, ht, start, length, va)
        if verbose:
            print(
                "  H=%-4d epoch %2d  loss %.4f  val top1 %.3f  rho %.3f  MAE %.0f  R2 %.3f"
                % (hidden, epoch, tot / max(1, len(tr) // args.batch_groups),
                   m["top1"], m["spearman_mean"], m["mae"], m["r2"])
            )
        if m["top1"] > best[0]:
            best = (m["top1"], {k: v.clone() for k, v in model.state_dict().items()}, epoch)
        elif epoch - best[2] >= args.patience:
            break
    model.load_state_dict(best[1])
    return model, mu, sigma, best[2]


# --------------------------------------------------------------------------- export


def export(model, mu, sigma, meta, path):
    """Fold the target standardisation into w2/b2 so the file is in raw hand-tuned units."""
    w1 = model.emb.weight.detach().numpy().T  # (H, 1152)
    b1 = (model.b1.detach().numpy() + 0.0).tolist()
    w2 = (model.out.weight.detach().numpy().ravel() * sigma).tolist()
    b2 = float(model.out.bias.detach().numpy().ravel()[0] * sigma + mu)
    doc = {
        "meta": meta,
        "w1": [[float(v) for v in row] for row in w1],
        "b1": [float(v) for v in b1],
        "w2": [float(v) for v in w2],
        "b2": b2,
    }
    with open(path, "w") as f:
        json.dump(doc, f, allow_nan=False)
        f.write("\n")
    return doc


def fmt(name, m):
    return (
        "%-10s top1 %6.1f%%  rho_mean %6.3f  rho_med %6.3f  MAE %8.1f  "
        "MAE_within %7.1f  R2 %6.3f"
        % (name, 100 * m["top1"], m["spearman_mean"], m["spearman_median"], m["mae"],
           m["mae_within"], m["r2"])
    )


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("dataset", nargs="?", default="/tmp/nnue_v3_siblings.jsonl")
    p.add_argument("--hidden", type=int, default=32)
    p.add_argument("--sweep", type=int, nargs="*", default=None,
                   help="H values to sweep (default 0 16 32 64 128; 0 = linear baseline)")
    p.add_argument("--epochs", type=int, default=40)
    p.add_argument("--patience", type=int, default=6)
    p.add_argument("--lr", type=float, default=3e-3)
    p.add_argument("--weight-decay", type=float, default=1e-2)
    p.add_argument("--dropout", type=float, default=0.1)
    p.add_argument("--batch-groups", type=int, default=64)
    p.add_argument("--temp", type=float, default=1300.0,
                   help="ListNet temperature in HAND-TUNED units; default ~ the median sibling gap")
    p.add_argument("--mse-weight", type=float, default=10.0,
                   help="weight of the MSE unit anchor (see the blend table in docs/nnue-v3-net.md)")
    p.add_argument("--rank-weight", type=float, default=1.0,
                   help="weight of the ListNet term; 0 = pure value regression (the v3.1 control)")
    p.add_argument("--seed", type=int, default=0)
    p.add_argument("--ridge", type=float, nargs="*", default=None,
                   help="fit the v3.1 linear ridge baseline at these lambdas and stop "
                        "(default 1 10 100 1000)")
    p.add_argument("--out", default="", help="path to write nnue_v3_net.json; empty = measure only")
    args = p.parse_args(argv)

    data = load_dataset(args.dataset)
    start, length = groups(data["pos"])
    tr_mask, va_mask, te_mask = split_games(data["gidx"], args.seed)
    tr, va, te = (subset(start, length, m) for m in (tr_mask, va_mask, te_mask))
    print(
        "rows %d, groups %d (%d train / %d val / %d test), games %d (%d/%d/%d)"
        % (len(data["ht"]), len(start), len(tr), len(va), len(te),
           len(np.unique(data["gidx"])),
           len(np.unique(data["gidx"][tr_mask])), len(np.unique(data["gidx"][va_mask])),
           len(np.unique(data["gidx"][te_mask])))
    )
    print("loss: %g * ListNet(temp=%g ht units) + %g * MSE   wd=%g dropout=%g"
          % (args.rank_weight, args.temp, args.mse_weight, args.weight_decay, args.dropout))
    print("linear baseline on record (V3OrderingProbe): top1 %.1f%%  rho %.3f  "
          "MAE %.0f / R2 %.3f (parent positions, NOT comparable -- see --ridge)"
          % (100 * LINEAR_BASELINE["top1"], LINEAR_BASELINE["spearman"],
             LINEAR_BASELINE["mae"], LINEAR_BASELINE["r2"]))

    if args.ridge is not None:
        for lam in args.ridge or [1.0, 10.0, 100.0, 1000.0]:
            pred = ridge_baseline(data, start, length, tr, lam)
            print(fmt("ridge %-6g val" % lam, metrics(pred, data["ht"], start, length, va)))
            print(fmt("ridge %-6g TEST" % lam, metrics(pred, data["ht"], start, length, te)))
        return

    if args.sweep is None:
        hs = [args.hidden]
    else:
        hs = args.sweep or [0, 16, 32, 64, 128]
    results = {}
    for h in hs:
        model, mu, sigma, epoch = train(data, start, length, tr, va, args, h)
        pred = predict(model, data["active"]) * sigma + mu
        results[h] = {
            "val": metrics(pred, data["ht"], start, length, va),
            "test": metrics(pred, data["ht"], start, length, te),
            "epoch": epoch,
            "model": (model, mu, sigma),
        }
        print(fmt("H=%d val" % h, results[h]["val"]))
        print(fmt("H=%d TEST" % h, results[h]["test"]))

    # H is chosen on VAL. Picking it on test would make the reported test number a training metric.
    best_h = max(results, key=lambda h: results[h]["val"]["top1"])
    print("\n=== H sweep (selection on VAL top-1; TEST is the reported holdout) ===")
    print("%4s %6s %9s %9s %9s %9s %9s" % ("H", "epoch", "val_top1", "top1", "rho_mean", "MAE", "R2"))
    for h in hs:
        t = results[h]["test"]
        print("%4d %6d %8.1f%% %8.1f%% %9.3f %9.1f %9.3f"
              % (h, results[h]["epoch"], 100 * results[h]["val"]["top1"], 100 * t["top1"],
                 t["spearman_mean"], t["mae"], t["r2"]))
    print("chosen H = %d" % best_h)
    t = results[best_h]["test"]
    print(fmt("BEST TEST", t))
    print("GATE (top-1 >= 70%%): %s" % ("PASS" if t["top1"] >= 0.70 else "FAIL"))

    if args.out:
        if best_h == 0:
            raise SystemExit("--out needs a net; the sweep's best H was the linear baseline (0)")
        model, mu, sigma = results[best_h]["model"]
        meta = {
            "arch": "1152-%d-1" % best_h,
            "hidden": best_h,
            "activation": "relu",
            "features": N_FEATURES,
            "score_units": "hand_tuned",
            "games_train": int(len(np.unique(data["gidx"][tr_mask]))),
            "games_holdout": int(len(np.unique(data["gidx"][te_mask]))),
            "top1_holdout": t["top1"],
            "spearman_holdout": t["spearman_mean"],
            "mae_holdout": t["mae"],
            "r2_holdout": t["r2"],
        }
        export(model, mu, sigma, meta, args.out)
        print("wrote %s (H=%d, top1_holdout=%.4f)" % (args.out, best_h, t["top1"]))


if __name__ == "__main__":
    main()
