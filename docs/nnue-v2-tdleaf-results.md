# NNUE v2 — TD-leaf / search-bootstrap retrain (bd nnue-trainer-d4a.4.2)

The v2 eval trained on raw FINAL-outcome WDL loses the fixed-depth gauntlet
(0-24 vs HAND_TUNED at d3, bead d4a.1.4): a WDL outcome-predictor is a poor
SEARCH LEAF (tactically blind). The scale sweep (4.1) ruled out magnitude — the
in-tree ordering itself is weak. This retrains the eval on **deep-search values
(TD-leaf)** so a shallow v2 leaf carries deep-search-quality assessment.

## TD-leaf target design

Per emitted raw position we record, in addition to the final-outcome `wdl`, a
**`search_wdl`**: the mover's value from a fixed-depth GoBot search using the
**hand-tuned leaf**, squashed into STM WDL space.

- **Search depth**: `TDLEAF_DEPTH=4` (env; default 0 = disabled/unchanged). The
  GoBot search runs from the mover's perspective on a fresh full turn.
- **Leaf**: HAND_TUNED, pinned as the process default during raw generation, so
  the target is always the hand-tuned deep-search value regardless of ambient
  config. (Depth-4 hand-tuned search is *deeper* than the shallow gauntlet leaf
  it must beat — distilling hand-tuned's own static eval would only tie it.)
- **WDL mapping** (score → [0,1]): logistic `search_wdl = 1/(1+exp(-score/S))`
  with `TDLEAF_WDL_SCALE S=6000`. Calibrated to the real hand-tuned band on
  12x12 self-play (abs score median ~7k, p90 ~24k): `S=6000` keeps ~41% of
  positions gradated in [0.2,0.8] instead of the ~98% saturation a naive `S=1000`
  produces (which collapses the target back to a sign bit).
- **Training target** (the TD-leaf blend, applied in `extract_examples
  --tdlambda`): `target = (1-λ)·search_wdl + λ·outcome_wdl`, with
  **`TDLEAF_LAMBDA λ=0.3`** — 70% deep-search signal, 30% final outcome. No
  `search_wdl` or no `--tdlambda` ⇒ pure outcome (back-compat; `train_v2`
  unchanged).

## Corpus generated

`scripts/gen_v2_corpus.sh` with `TDLEAF_DEPTH=4 TDLEAF_WDL_SCALE=6000
NUM_GAMES=40 MAX_TURNS=150 EPSILON=0.25 EXPLORE_TURNS=100 BASE_SEED=7`
(deterministic; seed offset per size).

- **12,683 positions**, 5 board sizes: 12x12 (1398), 9x9 (2982), 7x7 (2590),
  5x5 (2991), 5x7 (2722). All 12,683 carry `search_wdl` (depth-4 hand-tuned).
- Blended-target distribution: mean 0.684, median 0.674 (smooth/gradated, vs the
  binary 0/0.5/1 of pure outcome).
- Diversity via negamax-path epsilon exploration (the raw-corpus path is
  size-agnostic; `EXPLORE_TEMP` only affects the GoBot self-play path, so
  diversity here is `EPSILON`/`EXPLORE_TURNS`, not temp).

Heavy artifacts (corpus JSONL, examples, `.pt`, 352 MB weights blob) are
gitignored — regenerate with the commands below.

## Dictionary / extract / train

- Dictionary: **8,305 patterns** @ min-count 8 (retained coverage 45.4%).
- Extract: `--tdlambda 0.3` → 12,683 examples.
- Train: 12 epochs, W=1024, seed=0, val_frac=0.2.

## Offline metrics (on the TD-leaf target)

| metric | value |
|--------|-------|
| train MSE | 0.0092 |
| val MSE | 0.0180 |
| const-floor MSE | 0.0531 (beats floor: yes) |
| **dir-acc (decisive)** | **0.9721** |
| mean train target | 0.685 |

(The lower MSE vs the outcome-labeled runs is expected — the blended target is
smoother, so absolute MSE is not comparable across label schemes; dir-acc and
the gauntlet are the honest signals.)

## Gauntlet: v2(new TD-leaf) vs HAND_TUNED

Fixed depth, 24 games, seed 7. `NNUEV2_WEIGHTS`/`NNUEV2_DICT` → the new blob+dict.

| depth | W-L-D (v2) | win% (v2) | old (outcome-WDL) |
|-------|-----------|-----------|-------------------|
| 3 | 0-24-0 | **0.0%** | 0-24 → 0% |
| 4 | 0-24-0 | **0.0%** | 0-12 → 0% |

**Both depths: still 0-24 — NO improvement over the outcome-WDL run.** Brutally
honest: TD-leaf targeting as implemented here did not lift v2's win% off the
floor at fixed depth 3 or 4 (24 games each, seed 7). Direction accuracy on
decisive positions is high (0.97), but that measures the easy end; the gauntlet
is decided by move *ordering* among near-equal middlegame positions, and the
blended+squashed WDL target is too compressed there (mean ~0.68, ~59% of targets
within the analyzer's draw band near 0.5) to give the search sharp preferences.

## Why it didn't work (diagnosis)

The TD-leaf *theory* is sound (distill deep hand-tuned search into a fast leaf so
v2-leaf-at-d3 ≈ hand-tuned-at-d7 > hand-tuned-at-d3). The *implementation* loses
too much of that signal before it reaches move ordering:

1. **Target compression/bias.** The hand-tuned depth-4 values are logistic-
   squashed to WDL and mean ~0.68 with a heavy mid-cluster; the net regresses
   toward that mean, so its output *variance across sibling moves* is small —
   exactly the gradient the search needs to prefer one move over another. WDL
   space throws away the magnitude ordering that the raw centipawn score had.
2. **12x12 is 11% of the corpus** (1398/12683). The gauntlet is 12x12 but most
   training positions are small boards. The eval is size-agnostic, but 12x12
   tactical patterns are underrepresented for the board it's judged on.
3. **λ=0.3 dilutes** the deep-search signal with 30% noisy final outcome —
   pulling the target back toward the very outcome-WDL signal that already lost.

## Next lever (recommended, in order)

1. **Regress the raw centipawn score, not WDL** — drop the logistic squash;
   train the leaf directly on the (normalized) hand-tuned depth-N score so
   magnitude ordering survives. This is the single most likely fix.
2. **λ→0** (pure search bootstrap) and **deeper `TDLEAF_DEPTH` (6–8)** — a
   stronger, undiluted teacher.
3. **12x12-heavy corpus** — generate the bulk of positions at the gauntlet size.
4. More capacity per bead d4a.4.3 only after 1–3.

## Regenerate

```bash
# 1. corpus with TD-leaf targets (CPU job, ~few min; deterministic)
TDLEAF_DEPTH=4 TDLEAF_WDL_SCALE=6000 NUM_GAMES=40 MAX_TURNS=150 \
  EPSILON=0.25 EXPLORE_TURNS=100 BASE_SEED=7 \
  OUT=python/v2/corpus/corpus_tdleaf.jsonl scripts/gen_v2_corpus.sh

# 2. dictionary (8,305 patterns @ min-count 8)
python3 python/v2/mine_patterns.py --corpus python/v2/corpus/corpus_tdleaf.jsonl \
  --min-count 8 --out python/v2/nnue_v2_dictionary.json

# 3. extract with the TD-leaf blend (lambda 0.3)
python3 python/v2/extract_examples.py --corpus python/v2/corpus/corpus_tdleaf.jsonl \
  --dict python/v2/nnue_v2_dictionary.json --tdlambda 0.3 \
  --out python/v2/nnue_v2_examples.jsonl

# 4. train + offline metrics
python3 python/v2/train_v2.py --examples python/v2/nnue_v2_examples.jsonl \
  --dict python/v2/nnue_v2_dictionary.json --epochs 12
python3 python/v2/analyze_metrics.py --examples python/v2/nnue_v2_examples.jsonl \
  --dict python/v2/nnue_v2_dictionary.json --model python/v2/nnue_v2_model.pt

# 5. export Java weights (gitignored 352 MB blob)
python3 -m python.v2.export_weights --model python/v2/nnue_v2_model.pt \
  --dict python/v2/nnue_v2_dictionary.json --width 1024

# 6. gauntlet vs hand-tuned
MATCHUP=bar NNUEV2_WEIGHTS=python/v2/nnue_v2_weights.json \
  NNUEV2_DICT=python/v2/nnue_v2_dictionary.json \
  java -cp "target/classes:$CP" com.engine.nnue_trainer.train.GauntletV2Run 24 3,4 7
```
