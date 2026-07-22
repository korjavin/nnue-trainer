"""Train the NNUE value net (864 -> HIDDEN -> 1, clipped ReLU, MSE) on the
imported self-play dataset and export weights for the Java engine.

Vectorised with numpy. Run with the venv interpreter: .venv/bin/python train.py

Adds a seeded train/val split, L2 weight decay, configurable hidden size, and
early stopping so we can measure generalization (val MSE) and right-size the
model. Export format is unchanged (hiddenWeights/hiddenBiases/outputWeights/
outputBias); NNUEModel.java derives the hidden size from the array length, so
a smaller hidden layer loads with no Java change.

  .venv/bin/python train.py          # train default config, export weights
  .venv/bin/python train.py --sweep  # hidden-size x weight-decay sweep, no export

Requires: dataset.json (from import_games.py). Writes src/main/resources/nnue_weights.json.
"""
import json
import os
import sys
import numpy as np

INPUT_SIZE = 864
HIDDEN_SIZE = 256
EPOCHS = 40
BATCH_SIZE = 256
INITIAL_LR = 0.01
SEED = 0
VAL_FRAC = 0.15
WEIGHT_DECAY = 0.0
PATIENCE = 6          # early stop after this many epochs with no val improvement

# Repo-relative defaults (this script lives at the repo root). Override with env
# vars DATASET / OUT_PATH so the maintainer can point at any dataset/weights file.
_REPO = os.path.dirname(os.path.abspath(__file__))
DATASET = os.environ.get("DATASET", os.path.join(_REPO, "dataset.json"))
OUT_PATH = os.environ.get(
    "OUT_PATH", os.path.join(_REPO, "src/main/resources/nnue_weights.json"))


def clipped_relu(x):
    return np.clip(x, 0.0, 127.0)


def load_data():
    with open(DATASET) as f:
        data = json.load(f)
    X = np.asarray([d["features"] for d in data], dtype=np.float32)
    y = np.asarray([d["target"] for d in data], dtype=np.float32)
    return X, y


def split(X, y, val_frac=VAL_FRAC, seed=SEED):
    """Seeded held-out split. Same seed => same split, so runs are comparable."""
    n = len(y)
    idx = np.random.default_rng(seed).permutation(n)
    n_val = int(round(n * val_frac))
    vi, ti = idx[:n_val], idx[n_val:]
    return X[ti], y[ti], X[vi], y[vi]


def mse(W1, b1, w2, b2, X, y):
    out = clipped_relu(X @ W1.T + b1) @ w2 + b2
    return float(((out - y) ** 2).mean())


def train(Xtr, ytr, Xval, yval, hidden=HIDDEN_SIZE, weight_decay=WEIGHT_DECAY,
          epochs=EPOCHS, patience=PATIENCE, seed=SEED, verbose=False):
    """Train one model; return (best_val_mse, train_mse_at_best, best_weights)."""
    rng = np.random.default_rng(seed)
    n = len(ytr)
    W1 = rng.normal(0.0, np.sqrt(2.0 / INPUT_SIZE), (hidden, INPUT_SIZE)).astype(np.float32)
    b1 = np.zeros(hidden, dtype=np.float32)
    w2 = rng.normal(0.0, np.sqrt(2.0 / hidden), hidden).astype(np.float32)
    b2 = np.float32(0.0)

    best = (float("inf"), float("inf"), None)   # (val_mse, train_mse, weights)
    stale = 0
    for epoch in range(epochs):
        lr = INITIAL_LR / (1.0 + 0.1 * epoch)
        idx = rng.permutation(n)
        for start in range(0, n, BATCH_SIZE):
            bi = idx[start:start + BATCH_SIZE]
            xb, yb = Xtr[bi], ytr[bi]
            m = len(yb)

            pre = xb @ W1.T + b1
            h = clipped_relu(pre)
            out = h @ w2 + b2

            err = out - yb
            d_out = err / m
            dw2 = h.T @ d_out + weight_decay * w2      # L2 decay on weights only
            db2 = d_out.sum()
            dh = np.outer(d_out, w2)
            dh *= (pre >= 0.0) & (pre <= 127.0)
            dw1 = dh.T @ xb + weight_decay * W1
            db1 = dh.sum(axis=0)

            W1 -= lr * dw1
            b1 -= lr * db1
            w2 -= lr * dw2
            b2 -= lr * db2

        tr = mse(W1, b1, w2, b2, Xtr, ytr)
        val = mse(W1, b1, w2, b2, Xval, yval)
        if verbose:
            print(f"  epoch {epoch + 1:2d}/{epochs}  train {tr:.5f}  val {val:.5f}")
        if val < best[0] - 1e-5:
            best = (val, tr, (W1.copy(), b1.copy(), w2.copy(), np.float32(b2)))
            stale = 0
        else:
            stale += 1
            if stale >= patience:
                if verbose:
                    print(f"  early stop at epoch {epoch + 1}")
                break
    return best


def export(weights, path=OUT_PATH):
    W1, b1, w2, b2 = weights
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump({
            "hiddenWeights": W1.tolist(),
            "hiddenBiases": b1.tolist(),
            "outputWeights": w2.tolist(),
            "outputBias": float(b2),
        }, f)
    print(f"Saved trained NNUE weights to {path}")


def sweep():
    X, y = load_data()
    Xtr, ytr, Xval, yval = split(X, y)
    print(f"Loaded {len(y)} positions | train={len(ytr)} val={len(yval)} | "
          f"targets mean={y.mean():.3f}")
    # Label-noise floor: best constant predictor on val = predict mean(train).
    floor = float(((yval - ytr.mean()) ** 2).mean())
    print(f"Constant-predictor val MSE (label-noise upper bound): {floor:.5f}\n")

    print(f"{'hidden':>6} {'wd':>7} {'train':>8} {'val':>8} {'gap':>8}")
    best_cfg = None
    for hidden in (256, 64, 16):
        for wd in (0.0, 1e-4, 1e-3):
            val, tr, _ = train(Xtr, ytr, Xval, yval, hidden=hidden, weight_decay=wd)
            gap = val - tr
            print(f"{hidden:>6} {wd:>7.0e} {tr:>8.5f} {val:>8.5f} {gap:>+8.5f}")
            if best_cfg is None or val < best_cfg[0]:
                best_cfg = (val, hidden, wd, tr, gap)
    val, hidden, wd, tr, gap = best_cfg
    print(f"\nBest val MSE: hidden={hidden} wd={wd:.0e} -> "
          f"val {val:.5f} (train {tr:.5f}, gap {gap:+.5f})")


def main():
    if not os.path.exists(DATASET):
        print(f"Error: {DATASET} not found. Run import_games.py first.")
        return
    if "--sweep" in sys.argv:
        sweep()
        return

    X, y = load_data()
    Xtr, ytr, Xval, yval = split(X, y)
    print(f"Loaded {len(y)} positions | train={len(ytr)} val={len(yval)}")
    # Label-noise floor: best constant predictor on val = predict mean(train).
    floor = float(((yval - ytr.mean()) ** 2).mean())
    print(f"Constant-predictor val MSE (label-noise upper bound): {floor:.5f}")
    val, tr, weights = train(Xtr, ytr, Xval, yval, verbose=True)
    print(f"Best: train MSE {tr:.5f}  val MSE {val:.5f}")
    export(weights)


if __name__ == "__main__":
    main()
