# NNUE v2 — GOOD-corpus training results (bd nnue-trainer-d4a.2.5)

First HONEST strength-representation numbers on a corpus that is **both diverse
and correctly labeled** (real win/loss via `GoState.outcomeWinner`, not the
old 94%-draw labels).

## Regenerate (all heavy artifacts are gitignored)

```bash
# 1. corpus (~23k positions, 5 board sizes)  — CPU job, a few minutes
export EXPLORE_TEMP=0.6 EXPLORE_TURNS=100 NUM_GAMES=40 MAX_TURNS=150
scripts/gen_v2_corpus.sh                       # -> python/v2/corpus/corpus.jsonl

# 2. mine dictionary (8135 patterns @ min-count 20)
python3 python/v2/mine_patterns.py --corpus python/v2/corpus/corpus.jsonl \
  --min-count 20 --out python/v2/nnue_v2_dictionary.json

# 3. extract examples (real decisive WDL)
python3 python/v2/extract_examples.py --corpus python/v2/corpus/corpus.jsonl \
  --dict python/v2/nnue_v2_dictionary.json --out python/v2/nnue_v2_examples.jsonl

# 4. train (12 epochs, W=1024, seed=0, val_frac=0.2)
python3 python/v2/train_v2.py --examples python/v2/nnue_v2_examples.jsonl \
  --dict python/v2/nnue_v2_dictionary.json --epochs 12

# honest extra metrics (dir-acc / floor / class balance)
python3 python/v2/analyze_metrics.py --examples python/v2/nnue_v2_examples.jsonl \
  --dict python/v2/nnue_v2_dictionary.json --model python/v2/nnue_v2_model.pt

# 5. export Java-loadable weights
python3 -m python.v2.export_weights --model python/v2/nnue_v2_model.pt \
  --dict python/v2/nnue_v2_dictionary.json --width 1024
```

## 1. Corpus (23,024 positions, 5 sizes)

Overall draw-rate **9.6%** (was ~94% in the pre-fix run). Per-size win/loss
near-balanced:

| size  | n     | win  | draw | loss |
|-------|-------|------|------|------|
| 12x12 | 1554  | 52%  | 0%   | 48%  |
| 9x9   | 6547  | 46%  | 10%  | 44%  |
| 7x7   | 5320  | 48%  | 5%   | 47%  |
| 5x7   | 4805  | 46%  | 9%   | 45%  |
| 5x5   | 4798  | 41%  | 19%  | 40%  |

Diversity: 12x12 self-play distinct-game ratio 0.16 with 100% unique-position
yield (dedup on). (The `0/0 unique yield` line for non-12x12 sizes is a
reporting quirk of the `EMIT=raw` path, not empty output — the positions were
written and are decisive-balanced above.)

## 2. Dictionary

8,135 patterns @ min-count 20. Retained coverage 29.3% of 5x5 windows
(1.16M windows total). The lower coverage vs the old dict is the price of a
genuinely diverse corpus — many patterns are rare.

## 3. Training

12 epochs, 23,024 examples, W=1024, seed=0, val_frac=0.2.

| metric | GOOD corpus (this run) | interim (d4a.2.4-ish) | pre-fix 94%-draw run |
|--------|------------------------|-----------------------|----------------------|
| examples | 23,024 | 1,048 | small, ~94% draws |
| val MSE | **0.0613** | 0.0981 | ~floor (no signal) |
| const-predictor floor | 0.2266 | 0.2445 | ~0.05 (trivially low: predict "draw") |
| beats floor? | **yes (0.061 << 0.227)** | yes | meaningless — floor already ~0 |
| directional acc (decisive val) | **0.9175** | 0.886 | undefined (few decisive) |
| class balance (win/draw/loss) | **45.9 / 9.6 / 44.5** | — | ~3 / 94 / 3 |

**Verdict: it learns REAL win/loss signal.** The labels are genuinely decisive
and near-symmetric (46/10/44), so the constant-predictor floor is high (0.227 —
you cannot win by always guessing "draw" anymore), and the model sits far below
it (0.061) with 91.75% directional accuracy on decisive val positions. This is
the honest opposite of the 94%-draw run, where a "predict draw" constant hit a
near-zero floor and the net learned nothing about who actually won.

Caveat (unchanged framing): this validates **representation + labeling
quality**, not competitive playing strength. Directional accuracy is on the
held-out split of self-play positions labeled by final territory outcome.
