"""Convert staticevalgen JSONL (board + mover + GoBot static eval) into a
train.py dataset.json: 864 features (via import_games.map_board_to_features,
same encoding as Java BoardFeatureMapper) paired with a normalized eval target.

Normalization: the raw eval is unbounded and heavy-tailed; z-score then scale to
~unit range keeps gradients sane (the Java net output is unbounded so any
consistent scale works, as long as SCALE is applied identically at export time).
We DON'T squash with tanh: the search only needs a monotone-in-advantage score,
and a linear target keeps distillation MSE interpretable. Robust std (MAD) so a
few large-but-legit evals don't dominate.

Run: .venv/bin/python make_distill_dataset.py /tmp/staticeval.jsonl
Writes dataset.json and prints the mean/std used (record it for export sanity).
"""
import json
import sys
import numpy as np
from import_games import map_board_to_features

SRC = sys.argv[1] if len(sys.argv) > 1 else "/tmp/staticeval.jsonl"
OUT = "/Users/iv/Projects/nnue-trainer/dataset.json"


def main():
    boards, players, scores = [], [], []
    with open(SRC) as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            rec = json.loads(line)
            boards.append(rec["board"])
            players.append(rec["player"])
            scores.append(rec["score"])

    scores = np.asarray(scores, dtype=np.float64)
    print(f"Loaded {len(scores)} positions | raw score "
          f"min={scores.min():.0f} max={scores.max():.0f} "
          f"mean={scores.mean():.1f} std={scores.std():.1f}")

    # Standardize: center + scale by std so the target lands in roughly [-3,3].
    mean = float(scores.mean())
    std = float(scores.std()) or 1.0
    targets = (scores - mean) / std
    print(f"Normalized target: mean={targets.mean():.4f} std={targets.std():.4f} "
          f"| (label = (rawScore - {mean:.2f}) / {std:.2f})")

    dataset = []
    for board, player, t in zip(boards, players, targets):
        feats = map_board_to_features(board, player)
        dataset.append({"features": feats, "target": float(t)})

    with open(OUT, "w") as f:
        json.dump(dataset, f)
    print(f"Wrote {len(dataset)} records to {OUT}")


if __name__ == "__main__":
    main()
