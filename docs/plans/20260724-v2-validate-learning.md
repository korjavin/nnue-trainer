# NNUE v2 Validation and Board-Size Independence Proof

## Overview

Bead nnue-trainer-d4a.2.3. Produce `python/v2/validate_v2.py` and a committed
report `docs/nnue-v2-validation.md` that answer two questions:

1. Does the v2 sparse-pattern representation learn useful signal (train/val MSE
   curves, dictionary size 5571, dataset size 1048), and is that enough to
   justify continuing toward runtime integration + quantization?
2. Is the trained model board-size independent — does the SAME model + SAME
   dictionary produce finite, shape-correct evals on NON-12x12 boards with NO
   code changes and NO 12x12 assumptions?

Plus an honest v1-vs-v2 comparison on the 12x12 corpus (different feature
spaces and label spaces — apples-to-oranges, stated plainly).

**Honest framing (must appear in the report):** the corpus is 1048 examples
whose WDL labels come from the SIGN of v1's continuous target (no real draws,
tiny data). This validates REPRESENTATION + PLUMBING + board-size generality,
NOT competitive strength. The strength verdict is deferred to the larger
real-outcome multi-size corpus (bead d4a.3.4) and an in-engine gauntlet.

## Context (from discovery)

- Reuse only, no new deps:
  - `python/v2/train_v2.py` — `NNUEv2`, `collate`, `train_model`, `read_num_patterns`,
    `load_examples`, `set_seed`, `_eval_mse`. Config: W=1024, epochs=20,
    batch=64, lr=1e-3, seed=0, val_frac=0.2. MSE on WDL {0,0.5,1}.
  - `python/v2/extract_examples.py` — `load_dictionary`, `pattern_counts`,
    `board_to_grid`. (`pattern_counts(board, owner, pattern_to_id)` and
    `board_to_grid(board)` are the reusable size-agnostic forward-prep helpers.)
  - `python/v2/pattern_contract.py` — `Board`, `Cell`, `CellKind`,
    `PatternContract` (size-agnostic window extraction; distance bucket derived
    from enemy-base Manhattan distance).
  - `python/v2/dense_features.py` — `extract_dense_features(grid, active_player,
    turn_number, rows, cols)`. Note: feature[13] is `total_area/144.0`, a benign
    reference-scale constant (finite for all sizes) — NOT a 12x12 assertion; do
    not modify it.
  - `train.py` (repo root, v1) — `load_data`, `split`, `train`. v1: 864 one-hot,
    HIDDEN=256, MSE on CONTINUOUS target in [-1,1], seed=0, val_frac=0.15.
  - `nnue_v2_dictionary.json` metadata: num_patterns=5571, min_count=5.
  - `dataset.json` (gitignored, repo root): 1048 records, 864-dim, side=12,
    targets in {-1,+1} (650 pos / 398 neg; no zeros → no draws).
- Verified end-to-end already: a tiny v2 model forward-evaluates 5x5, 5x7, 7x9
  synthesized boards to finite scalars with no code change. Small boards match
  few/zero dictionary patterns (dictionary is 12x12-mined) but still evaluate
  via the dense channel + zeroed accumulators — an honest caveat, and itself
  proof the accumulator is size-agnostic by construction.

## Development Approach

- **Testing approach**: Regular (code first, then a focused test).
- Deterministic everywhere: seed=0, fixed board constructions.
- No 12x12 hardcode anywhere in new code — the board-size proof depends on it.
- Reuse existing train/extract/contract/dense code; do not reimplement.
- All tests pass before finishing.

## Testing Strategy

- **Unit test** `python/v2/validate_v2_test.py`: assert the board-size
  demonstration returns finite, correctly-shaped evals for at least two
  non-12x12 sizes, and that results are deterministic across two runs. Keep it
  fast (tiny W, 1-2 epochs, or a randomly-initialized model — no full train).

## Progress Tracking

- Mark completed items `[x]` immediately.
- ➕ for newly discovered tasks, ⚠️ for blockers.

## What Goes Where

- Implementation Steps below have checkboxes (agent-automatable).
- Manual/gauntlet strength evaluation is Post-Completion (no checkbox).

## Implementation Steps

### Task 1: Add backward-compatible epoch-history hook to train_v2.train_model
- [x] In `python/v2/train_v2.py`, add an optional `on_epoch=None` keyword param
      to `train_model`. When provided, after each epoch call
      `on_epoch(epoch_index, train_mse, val_mse)` (compute both via the existing
      `_eval_mse` on the train and val splits). When `None`, behavior and return
      value are byte-for-byte unchanged (no extra computation).
- [x] Do not change the return signature `(model, train_mse, val_mse)`.
- [x] Confirm existing `python/v2/train_v2_test.py` still passes unchanged.
- [x] run `python3 -m unittest discover -s python/v2 -p "*_test.py"` — must pass.

### Task 2: validate_v2.py — v2 learning curves + v1 comparison
- [x] Create `python/v2/validate_v2.py`. Add `run_v2_training(...)`: load the
      dictionary (`read_num_patterns`) and examples (`load_examples`), train with
      the real config (W=1024, epochs=20, batch=64, lr=1e-3, seed=0,
      val_frac=0.2) passing an `on_epoch` collector to capture per-epoch
      (train_mse, val_mse). Return the trained model, the curve list, final
      metrics, dictionary size, and dataset size. Also compute a constant-
      predictor val MSE baseline (predict mean train WDL) as a learning floor,
      and a directional accuracy on the v2 val split ((pred>0.5) vs (wdl>0.5)).
- [x] Add `run_v1_baseline(...)`: import `train` (repo-root v1), call
      `load_data`/`split`/`train` with seed=0 to get v1 best val MSE + train MSE,
      plus v1 directional accuracy on its val split (sign(pred) vs sign(target))
      and its constant-predictor floor. Wrap in try/except so a missing
      `dataset.json` degrades gracefully (report "v1 baseline unavailable").
- [x] Ensure `dataset.json` path resolves to repo root; the script must not
      assume a 12x12 side anywhere (v1 side is derived by existing code).
- [x] run `python3 -m unittest discover -s python/v2 -p "*_test.py"` — must pass.

### Task 3: validate_v2.py — board-size independence proof
- [x] Add `synth_board(rows, cols)` building a `Board` with both bases, a small
      INTERIOR cluster of NORMAL_SELF/NORMAL_OPPONENT cells (dense enough that
      interior 5x5 windows can match the 12x12-mined dictionary), and a neutral —
      all placements derived from `rows`/`cols`, zero hardcoded 12.
- [x] Add `eval_board(model, board, pattern_to_id)`: build STM/NSTM counts via
      `extract_examples.pattern_counts`, dense via `board_to_grid` +
      `extract_dense_features(rows=board.rows, cols=board.cols)`, `collate` a
      single example, run `model.forward` under `torch.no_grad()`, and return
      `{rows, cols, stm_matched, nstm_matched, eval, finite}`.
- [x] Add `board_size_proof(model, pattern_to_id, sizes)` running at least the
      sizes `[(5,5),(5,7),(7,9),(9,9)]` (>=2 non-12x12) plus 12x12 as a sanity
      anchor. Assert every eval is finite and scalar-shaped. Return the per-size
      rows for the report.
- [x] Create `python/v2/validate_v2_test.py`: a randomly-initialized (untrained,
      tiny-W) `NNUEv2` runs `board_size_proof` over two non-12x12 sizes; assert
      finiteness, correct shape, and determinism across two identical runs. Fast.
- [x] run `python3 -m unittest discover -s python/v2 -p "*_test.py"` — must pass.

### Task 4: validate_v2.py — report generation + main()
- [ ] Add `write_report(path, v2_result, v1_result, proof_rows)` that writes
      `docs/nnue-v2-validation.md` containing: (a) a learning-curves table
      (epoch, train MSE, val MSE) + final metrics + constant-predictor floor +
      directional accuracy + the explicit learning VERDICT; (b) a v1-vs-v2
      comparison table with the caveats block (different feature spaces;
      v1 continuous target in [-1,1] vs v2 WDL in {0,0.5,1}; MSE not directly
      comparable; directional accuracy given as the closest common-ground
      metric; no overclaim); (c) the board-size-independence section: the
      per-size table (rows×cols, STM/NSTM matched patterns, eval, finite) with
      the plain-English structural explanation (sparse accumulator is
      size-agnostic; distance bucket normalizes by board size; small boards
      match few dictionary patterns because it was 12x12-mined, yet still
      evaluate) and an explicit "PROVEN across sizes X, Y with no code changes"
      statement; (d) the HONEST FRAMING block: 1048 sign-derived WDL labels,
      representation+plumbing+size-generality validated, competitive strength
      DEFERRED to d4a.3.4 corpus + in-engine gauntlet.
- [ ] Add `main(argv=None)` wiring it end-to-end: run v2 training, v1 baseline,
      board-size proof, print the board-size demonstration to stdout, and write
      the report. Deterministic. Add `if __name__ == "__main__": main()`.
- [ ] run `python3 python/v2/validate_v2.py` end-to-end; confirm it prints the
      board-size demonstration and writes `docs/nnue-v2-validation.md` with real
      numbers.
- [ ] run `python3 -m unittest discover -s python/v2 -p "*_test.py"` — must pass.

### Task 5: Verify acceptance criteria
- [ ] `python3 python/v2/validate_v2.py` runs clean end-to-end, report written
      with populated learning curves, v1-vs-v2 numbers, and board-size table.
- [ ] Report includes the honest-framing and no-overclaim language, and the
      explicit board-size-independence PROVEN statement naming the non-12x12
      sizes that ran.
- [ ] `git grep -n "12" python/v2/validate_v2.py` shows no 12x12 hardcode in
      logic (12x12 may appear only as a listed sanity-anchor size / in prose).
- [ ] `python3 -m unittest discover -s python/v2 -p "*_test.py"` all green.
- [ ] Commit the generated `docs/nnue-v2-validation.md` (it is a deliverable,
      not gitignored).

## Technical Details

- v2 val MSE and v1 val MSE live in different label spaces; the report states
  this and uses directional accuracy as the fairest common-ground signal.
- Determinism: seed=0 for v2 (train_v2) and v1 (train.py). Board constructions
  are fixed functions of (rows, cols).
- `dataset.json` is gitignored; if absent, v1 baseline and v2 example
  regeneration degrade gracefully with a clear report note rather than crashing.

## Post-Completion

**Manual verification (deferred, no checkbox):**
- Competitive-strength verdict requires the larger real-outcome multi-size
  corpus from bead d4a.3.4 and an in-engine gauntlet. This validation does NOT
  and MUST NOT claim the bot is stronger.
