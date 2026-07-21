import json
import os
import numpy as np

# Copied from train.py
def clipped_relu(x):
    return np.clip(x, 0.0, 127.0)

def forward(W1, b1, w2, b2, X):
    out = clipped_relu(X @ W1.T + b1) @ w2 + b2
    return float(out)

def main():
    weights_path = "src/main/resources/nnue_weights.json"
    if not os.path.exists(weights_path):
        # We need weights to compute predictions
        print(f"Error: {weights_path} not found.")
        return

    with open(weights_path, "r") as f:
        weights = json.load(f)

    W1 = np.array(weights["hiddenWeights"], dtype=np.float32)
    b1 = np.array(weights["hiddenBiases"], dtype=np.float32)
    w2 = np.array(weights["outputWeights"], dtype=np.float32)
    b2 = np.float32(weights["outputBias"])

    rng = np.random.default_rng(42)
    test_cases = []

    # Generate 20 test vectors
    for _ in range(20):
        # Simulate realistic features: mostly 0s, some 1s.
        # 864 features
        features = np.zeros(864, dtype=np.float32)
        # Randomly activate some features (say 5 to 30)
        num_active = rng.integers(5, 30)
        active_indices = rng.choice(864, size=num_active, replace=False)
        features[active_indices] = 1.0

        expected_out = forward(W1, b1, w2, b2, features)

        test_cases.append({
            "features": features.tolist(),
            "expectedOutput": expected_out
        })

    out_path = "src/test/resources/nnue_parity_vectors.json"
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "w") as f:
        json.dump(test_cases, f)

    print(f"Saved 20 test cases to {out_path}")

if __name__ == "__main__":
    main()
