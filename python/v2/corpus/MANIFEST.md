# v2 raw self-play corpus — manifest

Canonical v2 raw-position corpus (JSONL) produced by `scripts/gen_v2_corpus.sh`
via `SelfPlayGenerator` in `EMIT=raw` mode. This is the scaled, multi-board-size
replacement for the thin 1048-position 12x12-only v1 mining input.

## Schema (one position per line)
```
{"rows":R,"cols":C,
 "cells":[[{"kind":"EMPTY|NEUTRAL|BASE|NORMAL|FORTIFIED","owner":<int|-1>}, ...C], ...R],
 "stm":<activePlayer int>, "wdl":<0.0|0.5|1.0 from STM perspective>}
```
`owner` is `-1` for EMPTY/NEUTRAL. `wdl` is from the side-to-move perspective
(win 1.0 / draw 0.5 / loss 0.0), labeled by the game's final result.

## Regeneration (deterministic — SEED offset per board size)
```bash
NUM_GAMES=100 MAX_TURNS=100 BASE_SEED=1 \
  OUT=python/v2/corpus/corpus.jsonl bash scripts/gen_v2_corpus.sh
```
Board sizes swept: 12x12, 9x9, 7x7, 5x5, 5x7 (`SEED = BASE_SEED + size_index`).

## What is committed
- `corpus.sample.jsonl` — a 300-line stratified slice (60 lines/board size) for
  schema/CI checks. The full `corpus.jsonl` (~181 MB, 122,382 positions) is
  **gitignored**; regenerate it with the command above.

## Generated corpus (NUM_GAMES=100, this run)
| board | positions |
|-------|-----------|
| 12x12 | 6,815 |
| 9x9   | 29,376 |
| 7x7   | 28,537 |
| 5x5   | 28,553 |
| 5x7   | 29,101 |
| **total** | **122,382** |

## Re-mining results (`mine_patterns.py --corpus corpus.jsonl`)
Full corpus: 6,050,014 windows, 360,243 distinct signatures.

| min_count | num_patterns | retained coverage |
|-----------|-------------:|------------------:|
| 5   | 107,426 | 92.67% |
| 20  |  60,905 | 86.18% |
| 50  |  21,260 | 58.91% |
| 100 |  12,562 | 47.04% |
| 200 |   3,295 | 26.41% |
| 300 |   1,699 | 19.81% |

### vs baseline (thin 1048-position 12x12 corpus): **5,571 patterns @ 65%**
- Same threshold as the baseline (`min_count=5`): **107,426 patterns @ 92.67%** —
  coverage rises 65% → 92.67% and the promoted vocabulary grows ~19x because the
  corpus is ~5,800x more windows and spans 5 board sizes.
- The 2k–10k prototype target (docs/nnue-v2-pattern-features.md) now needs a much
  higher threshold (~min_count 100–150). At an equal ~5.5k-pattern dictionary size
  coverage is *lower* than the 12x12-only baseline (~33%) because the multi-size
  corpus has a far heavier distinct-signature tail (edge/OOB windows differ per
  size) — expected, and more honest for a size-agnostic model.

**Dictionary promotion (which threshold to freeze into `nnue_v2_dictionary.json`)
is the owner's call** — the tracked dictionary was left unchanged.

### NSTM coverage caveat (per bead comment)
This corpus is mined single-perspective (`stm_owner = each position's stm`). If the
3.3 extractor's dual-perspective NSTM coverage measures low, re-mine counting both
perspectives (`stm_owner ∈ {1,2}`). Deferred; noted for the owner.
</content>
