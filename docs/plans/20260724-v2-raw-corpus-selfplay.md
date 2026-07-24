# NNUE v2: Scale training corpus via raw-board self-play generation

## Overview
The v2 pattern dictionary was mined from only 1048 decoded 12x12 v1 positions
(5571 patterns @ 65% coverage) — enough to prove the pipeline, too thin and too
12x12-biased to train a strong, board-size-agnostic model.

This plan adds a raw-board self-play emit path plus variable board sizes to
`SelfPlayGenerator.java`, teaches `python/v2/mine_patterns.py` to mine from that
raw corpus, and generates a real multi-board-size corpus to re-mine from.

Merge target: `nnue-v2-3.2-mining` branch (stacked PR), NOT master / v2.

## Context (from discovery)
- `src/main/java/com/engine/nnue_trainer/train/SelfPlayGenerator.java` — hardcodes
  `new Board(12,12)` + bases at (0,0)/(11,11) at lines ~195-199 (negamax path) and
  in `freshBoard()` ~390-393 (GoBot path). Emits only the v1 864-dim one-hot
  `TrainingRecord` via `saveDataset`.
- `src/test/java/com/engine/nnue_trainer/train/SelfPlayGeneratorTest.java` — existing
  tests; MUST stay green (v1 one-hot path intact).
- `python/v2/mine_patterns.py` — mines `dataset.json` (v1 one-hot) into a dict via
  `pattern_contract.py`. `count_signatures`/`build_dictionary`/`export_dictionary`
  are reusable and board-size-agnostic.
- `python/v2/pattern_contract.py` — `Board`, `Cell`, `CellKind`, `PatternContract`.
  CellKind: EMPTY/NORMAL/BASE/FORTIFIED/NEUTRAL.
- `python/v2/mine_patterns_test.py` — existing miner tests.
- Board model: `board/CellKind.java` (EMPTY/NORMAL/BASE/FORTIFIED/NEUTRAL),
  `board/Cell.java` (`int owner`, `CellKind kind`), `board/Board.java` (`rows`,`cols`).
- `.gitignore` already ignores `dataset.json` and `*.db`. Append-only for corpus.

### CANONICAL v2 RAW-POSITION SCHEMA (JSONL, one position/line) — the contract:
```
{"rows":R,"cols":C,
 "cells":[[{"kind":"EMPTY|NEUTRAL|BASE|NORMAL|FORTIFIED","owner":<int|-1>}, ...C], ...R],
 "stm":<activePlayer int>, "wdl":<0.0|0.5|1.0 from STM perspective>}
```
- owner = -1 for EMPTY/NEUTRAL (no owner), else the cell owner (1/2).
- wdl from STM perspective: winner==stm →1.0, draw →0.5, else →0.0.

## Development Approach
- Testing approach: Regular (code first, then tests).
- Preserve the v1 one-hot path exactly (other tooling depends on it).
- Do NOT touch `python/v2/extract_examples.py` or `v2/NNUEv2Accumulator.java`.
- No new 12x12 hardcode anywhere. Deterministic given a fixed SEED.
- Only append to `.gitignore`.

## Testing Strategy
- Unit tests: Java (raw emit schema + variable size), Python (corpus reader).
- No e2e/UI in this project for this path.

## Progress Tracking
- Mark completed items `[x]` immediately.
- ➕ new tasks, ⚠️ blockers.

## Implementation Steps

### Task 1: Variable board size in SelfPlayGenerator
- [x] add `int rows = 12; int cols = 12;` to `Config`; read `ROWS`/`COLS` env in `main`
- [x] add private helper `startBoard(int rows, int cols)` placing bases at (0,0) and
      (rows-1, cols-1); replace hardcoded `new Board(12,12)`+bases in `generate()` and
      `freshBoard()` to use `config.rows`/`config.cols` (freshBoard takes config)
- [x] ensure GoBot `playGoBotGames` uses `startBoard(config.rows, config.cols)`
- [x] write test: generate on a 7x7 board (Config.rows=cols=7) runs clean with no 12x12
      assumption. NOTE: the v1 864-dim one-hot mapper is 12x12-only, so its dataset is empty
      off-12x12 by design — the raw corpus (Task 2) carries non-12x12 positions. v1 record
      build is gated to 12x12 so games still play on any size and feed the raw path.
- [x] run `./mvnw test -Dtest=SelfPlayGeneratorTest` — passes (6/6; full suite 117/117 green)

### Task 2: Raw-board snapshot emit mode
- [x] add `RawCell{String kind; int owner;}` and `RawPosition{int rows,cols; RawCell[][] cells; int stm; double wdl;}` static classes
- [x] add `String rawOutPath` and `int rawSampleEvery=1` to `Config`; add
      `List<RawPosition> rawPositions` to `GenerationResult` (null unless requested)
- [x] in the negamax `generate()` path, when `config.rawOutPath != null`, sample the
      collected `turns` at stride `rawSampleEvery` and build `RawPosition` with wdl =
      (winner==0 ? 0.5 : winner==activePlayer ? 1.0 : 0.0), owner=-1 for EMPTY/NEUTRAL
- [x] add `saveRawCorpus(List<RawPosition>, path)` writing one compact JSON object per
      line (JSONL, no pretty print) via Jackson
- [x] wire `main`: read `RAW_OUT` (path), `RAW_SAMPLE_EVERY`; if `EMIT=raw` skip the v1
      `saveDataset` (else keep writing v1 one-hot as before)
- [x] write test: raw emit produces valid JSONL — each line parses, has rows/cols/stm,
      cells dims == rows×cols, wdl ∈ {0.0,0.5,1.0}, owner==-1 exactly for EMPTY/NEUTRAL
- [x] run `./mvnw test -Dtest=SelfPlayGeneratorTest` — passes (7/7)

### Task 3: mine_patterns.py --corpus reader
- [x] add `iter_boards_corpus(path)` in `mine_patterns.py`: read JSONL, map kind string
      → `CellKind`, set owner, build `pattern_contract.Board` (size from rows/cols)
- [x] mine each corpus board with `stm_owner = line["stm"]` (per-position perspective) —
      iter yields `(board, stm)` pairs; `count_signatures` uses per-position perspective
- [x] add `--corpus` arg to `main`; when given, use corpus iterator instead of
      `--dataset`; keep the dataset path working unchanged
- [x] write test in `mine_patterns_test.py`: a tiny inline JSONL corpus round-trips
      through `iter_boards_corpus` → `count_signatures` → `build_dictionary` (size-agnostic,
      e.g. a 7x7 line), asserting boards reconstruct and dictionary builds
- [x] run `python3 python/v2/mine_patterns_test.py` — passes (10/10; run with `PYTHONPATH=.`)

### Task 4: Corpus generation script + manifest
- [x] add `scripts/gen_v2_corpus.sh`: compile, then loop board sizes
      (12x12, 9x9, 7x7, 5x5, 5x7) invoking SelfPlayGenerator with ROWS/COLS/SEED/
      NUM_GAMES/EMIT=raw/RAW_OUT per size, concatenating into one corpus JSONL; print
      per-size + total line counts. Deterministic (SEED offset per size).
      Smoke run (NUM_GAMES=2): 746 positions across 5 sizes; mines to 1581 patterns @ 76% coverage.
- [x] append corpus artifacts (e.g. `python/v2/corpus/*.jsonl`) to `.gitignore` (append only)
- [x] run tests - full `SelfPlayGeneratorTest` + `mine_patterns_test.py` must pass (7/7, 10/10)

### Task 5: Verify acceptance criteria
- [x] `./mvnw test` green (SelfPlayGenerator tests pass, v1 path intact) — full suite exit 0
- [x] a small `RAW_OUT` run produces valid schema JSONL — gen_v2_corpus.sh NUM_GAMES=2:
      2436 positions across 5 sizes; all lines parse, cells dims == rows×cols, wdl ∈ {0,0.5,1},
      owner==-1 exactly for EMPTY/NEUTRAL (owner-mismatch=0)
- [x] `python3 python/v2/mine_patterns.py --corpus <sample>` runs clean — 1718 patterns @ 92.28%
      coverage on the smoke corpus (re-mined dict restored; promotion is owner's call)
- [x] run linters/formatters if configured — none configured for Python; Java build clean via mvnw

## Technical Details
- Raw JSONL is written compact (one line/position) via a Jackson `ObjectMapper`
  without the pretty printer used by `saveDataset`.
- `startBoard` centralizes base placement; no size literal survives.
- Corpus generation volume is controlled by NUM_GAMES per size; the actual corpus
  (potentially large) is gitignored — commit a small sample + a stats manifest, not
  a multi-hundred-MB blob.

## Post-Completion
**Manual/owner steps:**
- Actual large corpus generation is a CPU job run outside ralphex; commit only a
  sample + manifest + the generation command.
- Re-mine on the full corpus and report new dict size + coverage vs 5571@65%.
  Final dictionary promotion is the owner's call.
- Per bead comment: if measured NSTM coverage is low on the real corpus, consider
  mining both perspectives (stm_owner ∈ {1,2}). Deferred; noted for the owner.
