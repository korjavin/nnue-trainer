# NNUE v2: Extract Sparse Counted Training Examples with STM WDL Labels

## Overview
Build the NNUE v2 training-data extractor. For each sampled board position it emits
SPARSE COUNTED pattern IDs for BOTH the side-to-move (STM) and not-side-to-move
(NSTM) perspectives using the promoted dictionary `python/v2/nnue_v2_dictionary.json`,
attaches the 14 dense manual features, records board-size metadata (rows, cols), and
labels each example with a WDL target from the STM perspective
(win=1.0, draw=0.5, loss=0.0).

Bead: `nnue-trainer-d4a.3.3` (epic `nnue-trainer-d4a.3`). Merge target: the stacked
base branch `nnue-v2-3.2-mining`, NOT master, NOT v2.

Problem it solves: the two-accumulator pattern model (bead d4a.2.1) needs training
examples in the sparse-counted pattern representation. This extractor produces them
from the existing decoded corpus, and is board-size-agnostic so a future
larger/variable-size corpus (bead d4a.3.4) drops in unchanged.

## Context (from discovery)
- `python/v2/pattern_contract.py` — `PatternContract.extract_windows(board, stm_owner)`
  returns 5x5 `Window`s with perspective-normalized symbols + distance bucket. This is
  the ONLY signature/perspective source; do not reinvent.
- `python/v2/mine_patterns.py` — `decode_v1_record(features)` decodes an 864-dim v1
  one-hot record into a `pattern_contract.Board` (board size derived via
  `round(sqrt(len/6))`, never hardcoded); `window_signature(window)` produces the
  canonical signature string. Reuse both.
- `python/v2/nnue_v2_dictionary.json` — `{"pattern_to_id": {sig: id}, "metadata": {...}}`.
  No loader exists yet; a one-line `json.load` + `["pattern_to_id"]` is enough.
- `python/v2/dense_features.py` — `extract_dense_features(board, active_player,
  turn_number, rows, cols)` returns 14 floats, but expects a dict-grid board
  (`board[r][c]` -> `None` or `{'kind': str, 'owner': int}`), NOT a `pattern_contract.Board`.
  A tiny adapter converts the decoded Board to that grid.
- Dataset source: `dataset.json` at repo root, a list of records each `{"features":
  [864 floats], "target": float}`. `features` are already STM-normalized (owner 1=self,
  2=opponent). No explicit WDL/active_player/turn fields exist in this corpus.

## Development Approach
- Testing approach: Regular (code first, then tests) — logic is small and deterministic.
- Reuse existing helpers; add no new dependencies (stdlib json/argparse only).
- Complete each task fully; all tests pass before the next task.

## Testing Strategy
- Unit tests in `python/v2/extract_examples_test.py`, run via
  `python3 -m unittest discover -s python/v2 -p "*_test.py"`.
- No e2e/UI tests in this project.

## Progress Tracking
- Mark completed items `[x]` immediately.
- ➕ for newly discovered tasks, ⚠️ for blockers.

## Implementation Steps

### Task 1: Core extractor module `python/v2/extract_examples.py`
- [ ] Add repo-root `sys.path` shim (mirror `mine_patterns.py`) and import
      `decode_v1_record`, `window_signature` from `python.v2.mine_patterns`,
      `PatternContract` from `python.v2.pattern_contract`,
      `extract_dense_features` from `python.v2.dense_features`.
- [ ] `load_dictionary(path)` -> returns the `pattern_to_id` dict via `json.load`.
- [ ] `board_to_grid(board)` -> list-of-lists where each cell is `None` for EMPTY or
      `{'kind': cell.kind.name, 'owner': cell.owner}` otherwise (adapter for
      `extract_dense_features`); uses `board.rows`/`board.cols` (no hardcoded size).
- [ ] `pattern_counts(board, stm_owner, pattern_to_id)` -> `dict[str(id) -> count]`:
      iterate `extract_windows(board, stm_owner)`, compute `window_signature`, look up
      id, SKIP dict misses, aggregate per-occurrence counts (counted, NOT one-hot, NOT
      normalized for board size). Keys are stringified ids so JSON round-trips cleanly.
- [ ] `wdl_from_target(target)` -> `1.0` if `target > 0`, `0.0` if `target < 0`,
      else `0.5` (STM-perspective WDL reduction of the continuous target).
- [ ] `extract_example(record, pattern_to_id)` -> dict
      `{stm_pattern_counts, nstm_pattern_counts, dense (14 floats), rows, cols, wdl}`:
      decode board, STM counts via `stm_owner=1`, NSTM via `stm_owner=2`, dense via
      the grid adapter with `active_player=1, turn_number=0, rows=board.rows,
      cols=board.cols`.
- [ ] `iter_examples(dataset_path, pattern_to_id)` generator over all records in order.
- [ ] `main(argv)`: argparse `--dataset` (default repo-root `dataset.json`),
      `--dict` (default `python/v2/nnue_v2_dictionary.json`), `--out` (default
      `python/v2/nnue_v2_examples.jsonl`); write one JSON object per line
      (`sort_keys=True` for determinism); print example count and one sample record.
- [ ] run `python3 python/v2/extract_examples.py` end-to-end to confirm it writes.

### Task 2: Tests `python/v2/extract_examples_test.py`
- [ ] Test `pattern_counts`: build a small `Board`, assert counted (per-occurrence,
      >1 where a pattern repeats), and that a signature absent from the dict is skipped.
- [ ] Test `wdl_from_target`: positive->1.0, zero->0.5, negative->0.0.
- [ ] Test `extract_example` record shape: keys present, `len(dense)==14`, `rows`/`cols`
      match the decoded board size, STM vs NSTM counts differ for an asymmetric board.
- [ ] Test determinism: same input + dict yields byte-identical record dicts twice.
- [ ] Test board-size-agnostic: a 5x5 decoded record yields `rows==cols==5` (no 12x12
      hardcode).
- [ ] run `python3 -m unittest discover -s python/v2 -p "*_test.py"` — all pass.

### Task 3: Verify acceptance criteria
- [ ] Both perspectives present as counted sparse `{id:count}` maps; unseen patterns
      ignored; counts not normalized for board size.
- [ ] 14 dense features attached; rows/cols metadata present; WDL from STM perspective.
- [ ] Deterministic under fixed input + dictionary.
- [ ] Full test suite green; run the CLI showing example count + sample record.

## Technical Details
- Output artifact: `python/v2/nnue_v2_examples.jsonl`, one JSON object per line:
  `{"stm_pattern_counts": {"<id>": <count>}, "nstm_pattern_counts": {...},
    "dense": [14 floats], "rows": <int>, "cols": <int>, "wdl": <1.0|0.5|0.0>}`.
- Determinism: JSON written with `sort_keys=True`; Counter aggregation is order-stable
  because emitted counts are additive integers.

## Post-Completion
**Manual verification**: spot-check a sample record against the dictionary by hand.
**External**: the real WDL corpus (bead d4a.3.4) will replace the sign-of-target
reduction with true game outcomes; the extractor stays unchanged.
