import json
import os
import sys
import numpy as np

sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import train

INPUT_SIZE = train.INPUT_SIZE
OUT_PATH = "src/test/resources/nnue_parity_vectors.json"
WEIGHTS_PATH = "src/main/resources/nnue_weights.json"


def clipped_relu(x):
    return np.clip(x, 0.0, 127.0)


def forward(W1, b1, w2, b2, x):
    pre = x @ W1.T + b1
    h = clipped_relu(pre)
    out = h @ w2 + b2
    return float(out)


def main():
    if not os.path.exists(WEIGHTS_PATH):
        print(f"Error: {WEIGHTS_PATH} not found. Run train.py first.")
        return

    with open(WEIGHTS_PATH) as f:
        weights_data = json.load(f)

    W1 = np.array(weights_data["hiddenWeights"], dtype=np.float32)
    b1 = np.array(weights_data["hiddenBiases"], dtype=np.float32)
    w2 = np.array(weights_data["outputWeights"], dtype=np.float32)
    b2 = np.float32(weights_data["outputBias"])

    rng = np.random.default_rng(42)

    vectors = []

    # Generate 20 test vectors
    for i in range(20):
        # We need a vector with some 1s and mostly 0s, like the real game
        x = np.zeros(INPUT_SIZE, dtype=np.float32)

        # In a 12x12 board there are 144 cells, each can be 1 of 6 states
        # The input vector has 144*6 = 864 elements
        # For each of the 144 cells, randomly pick one of the 6 states, or none
        # (Wait, actually board feature mapper says one of the 6 states is 1.0f)
        for cell_idx in range(144):
            state_idx = rng.integers(0, 6) # 0 to 5
            x[cell_idx * 6 + state_idx] = 1.0

        prediction = forward(W1, b1, w2, b2, x)

        vectors.append({
            "features": x.tolist(),
            "expectedScore": prediction
        })

    os.makedirs(os.path.dirname(OUT_PATH), exist_ok=True)
    with open(OUT_PATH, "w") as f:
        json.dump(vectors, f, indent=2)

    print(f"Generated {len(vectors)} parity vectors at {OUT_PATH}")


if __name__ == "__main__":
    main()
