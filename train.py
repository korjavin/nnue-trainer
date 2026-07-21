"""Train the NNUE value net (864 -> 256 -> 1, clipped ReLU, MSE) on the
imported self-play dataset and export weights for the Java engine.

Same architecture and output format as the original pure-Python trainer, but
vectorised with numpy so it runs in seconds instead of hours. Run with the
venv interpreter: .venv/bin/python train.py

Requires: dataset.json (from import_games.py). Writes src/main/resources/nnue_weights.json.
"""
import json
import os
import numpy as np

INPUT_SIZE = 864
HIDDEN_SIZE = 256
EPOCHS = 40
BATCH_SIZE = 256
INITIAL_LR = 0.01
SEED = 0

DATASET = "/Users/iv/Projects/nnue-trainer/dataset.json"
OUT_PATH = "/Users/iv/Projects/nnue-trainer/src/main/resources/nnue_weights.json"


def clipped_relu(x):
    return np.clip(x, 0.0, 127.0)


def main():
    if not os.path.exists(DATASET):
        print(f"Error: {DATASET} not found. Run import_games.py first.")
        return

    with open(DATASET) as f:
        data = json.load(f)
    if not data:
        print("Error: dataset is empty.")
        return

    X = np.asarray([d["features"] for d in data], dtype=np.float32)
    y = np.asarray([d["target"] for d in data], dtype=np.float32)
    n = len(y)
    print(f"Loaded {n} positions | features={X.shape[1]} "
          f"| targets: mean={y.mean():.3f} (+1={int((y > 0).sum())}, -1={int((y < 0).sum())})")

    rng = np.random.default_rng(SEED)
    # Xavier/He-style init, matching the original scales.
    W1 = rng.normal(0.0, np.sqrt(2.0 / INPUT_SIZE), (HIDDEN_SIZE, INPUT_SIZE)).astype(np.float32)
    b1 = np.zeros(HIDDEN_SIZE, dtype=np.float32)
    w2 = rng.normal(0.0, np.sqrt(2.0 / HIDDEN_SIZE), HIDDEN_SIZE).astype(np.float32)
    b2 = np.float32(0.0)

    for epoch in range(EPOCHS):
        lr = INITIAL_LR / (1.0 + 0.1 * epoch)
        idx = rng.permutation(n)
        total_loss = 0.0
        for start in range(0, n, BATCH_SIZE):
            bi = idx[start:start + BATCH_SIZE]
            xb, yb = X[bi], y[bi]                      # (B,864), (B,)
            m = len(yb)

            pre = xb @ W1.T + b1                       # (B,256)
            h = clipped_relu(pre)
            out = h @ w2 + b2                          # (B,)

            err = out - yb                             # (B,)
            total_loss += float((err ** 2).sum())

            # Gradients (mean over batch; factor 2 folded into lr, as before).
            d_out = err / m                            # (B,)
            dw2 = h.T @ d_out                          # (256,)
            db2 = d_out.sum()
            dh = np.outer(d_out, w2)                   # (B,256)
            dh *= (pre >= 0.0) & (pre <= 127.0)        # clipped-ReLU grad
            dw1 = dh.T @ xb                            # (256,864)
            db1 = dh.sum(axis=0)

            W1 -= lr * dw1
            b1 -= lr * db1
            w2 -= lr * dw2
            b2 -= lr * db2

        print(f"Epoch {epoch + 1}/{EPOCHS} - MSE: {total_loss / n:.5f} (lr {lr:.5f})")

    os.makedirs(os.path.dirname(OUT_PATH), exist_ok=True)
    with open(OUT_PATH, "w") as f:
        json.dump({
            "hiddenWeights": W1.tolist(),
            "hiddenBiases": b1.tolist(),
            "outputWeights": w2.tolist(),
            "outputBias": float(b2),
        }, f)
    print(f"Saved trained NNUE weights to {OUT_PATH}")


if __name__ == "__main__":
    main()
