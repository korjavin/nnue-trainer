# PyTorch Two-Accumulator NNUE v2 Model + Training Script (nnue-trainer-d4a.2.1)

> **PR base = `nnue-v2-3.3-extract` (stacked), NOT master/v2.** Float-only prototype, no quantization/SIMD.

## Overview

Implement the float-first PyTorch NNUE v2 reference model and a deterministic
training script in a single file `python/v2/train_v2.py` (+ `train_v2_test.py`).

The model uses the Stockfish-style two-accumulator structure:

- STM counted pattern IDs -> `EmbeddingBag(num_patterns, W, mode='sum')` with
  `per_sample_weights=counts` -> `Acc_STM` (width W, default 1024).
- NSTM counted pattern IDs -> a **separate** `EmbeddingBag` -> `Acc_NSTM` (W).
- Concatenate `[Acc_STM(W), Acc_NSTM(W), dense(14)]` -> Linear -> 16 -> ReLU ->
  Linear -> 32 -> ReLU -> Linear -> 1. Train on WDL (win 1.0/draw 0.5/loss 0.0)
  with MSE.

`num_patterns` is read from `nnue_v2_dictionary.json` `metadata.num_patterns`
(currently 5571) — never hardcoded. Counted inputs feed EmbeddingBag as
per-sample weights (NOT one-hot, NOT normalized).

The script (1) runs / imports `python/v2/extract_examples.py` to regenerate the
gitignored `python/v2/nnue_v2_examples.jsonl`, (2) loads examples + dictionary
metadata, (3) runs deterministic float training (fixed torch+numpy+python seed)
with a train/val split, (4) saves model weights `.pt` + a metadata JSON (W,
num_patterns, dense_size, layer shapes), (5) reports train/validation MSE.

Honest framing: current examples derive WDL from the sign of v1's continuous
target (only 0.0/1.0 present, 1048 examples) — a PLUMBING PROOF. The script
must re-run UNCHANGED on the larger real-WDL corpus from bead d4a.3.4.

## Context (from discovery)

- Data contract per JSONL line (from `extract_examples.py`):
  `{stm_pattern_counts: {str(id):count}, nstm_pattern_counts: {str(id):count},
    dense: [14 floats], rows, cols, wdl}`.
- Dictionary: `python/v2/nnue_v2_dictionary.json` with `metadata.num_patterns`
  (5571) and `pattern_to_id`. IDs are 0..num_patterns-1.
- Corpus: `dataset.json` at repo root (gitignored, 1048 v1 records). WDL
  distribution {1.0: 650, 0.0: 398} — no draws in this proof corpus.
- torch is CPU-only, installed via `--break-system-packages`.
- Extractor `main(argv)` accepts `--dataset --dict --out`; `iter_examples()` is
  importable and yields example dicts directly.

## Development Approach

- Regular (code first, then tests).
- Single new source file `python/v2/train_v2.py` + `python/v2/train_v2_test.py`.
- No new dependencies beyond torch/numpy (already required for training).
- Handle dict-miss / empty-pattern-list example gracefully: EmbeddingBag needs
  valid offsets even for empty bags (an example with zero known patterns must
  produce a zero accumulator, not crash).
- Deterministic: seed torch, numpy, and python `random`; use
  `torch.use_deterministic_algorithms` where practical; fixed generator for the
  train/val split.

## Testing Strategy

- Unit tests only (`train_v2_test.py`), run via
  `python3 -m unittest discover -s python/v2 -p "*_test.py"`. No e2e framework.

## Progress Tracking

- Mark completed items `[x]` when done. Add ➕ for new tasks, ⚠️ for blockers.

## Implementation Steps

### Task 1: Model + sparse-batch collation in `train_v2.py`
- [x] create `python/v2/train_v2.py` with a `NNUEv2` `nn.Module`: two separate
      `nn.EmbeddingBag(num_patterns, W, mode='sum')` (STM, NSTM), then
      `Linear(2*W+14 -> 16) -> ReLU -> Linear(16 -> 32) -> ReLU -> Linear(32 -> 1)`.
      Constructor takes `num_patterns`, `W=1024`, `dense_size=14`.
- [x] `forward(stm_ids, stm_off, stm_w, nstm_ids, nstm_off, nstm_w, dense)`:
      call each EmbeddingBag with `per_sample_weights`, concat with dense, run
      the dense stack, return raw scalar per example.
- [x] add a collate helper turning a list of example dicts into the flat
      `(ids, offsets, per_sample_weights)` EmbeddingBag tensors for STM and NSTM
      plus a dense tensor and a wdl target tensor. Empty bags -> offset points
      past end / zero-length slice yielding a zero accumulator (no crash).
- [x] write test: forward pass output shape `(batch,)`; gradients flow to both
      EmbeddingBag weights after a backward pass.
- [x] write test: an example with empty `stm_pattern_counts` (dict-miss) yields
      a finite loss and a zero STM accumulator contribution (no NaN/crash).
- [x] run tests - must pass before next task

### Task 2: Data loading + deterministic training loop
- [ ] add `load_examples(path)` reading the JSONL; add a `regenerate_examples`
      path that imports `extract_examples` and calls its `main`/`iter_examples`
      to (re)produce the gitignored JSONL when missing or `--regenerate`.
- [ ] add `read_num_patterns(dict_path)` from `metadata.num_patterns` (assert
      present; never hardcode).
- [ ] add `set_seed(seed)` seeding python `random`, numpy, torch; deterministic
      train/val split (default 0.8/0.2) via a seeded generator.
- [ ] add `train(...)`: few epochs default (configurable `--epochs`,
      `--batch-size`, `--lr`, `--width`, `--seed`), MSE loss, Adam; return final
      train MSE and val MSE.
- [ ] write test: two runs with the same seed give identical train/val MSE
      (determinism), on a small synthetic in-memory example list.
- [ ] write test: `read_num_patterns` reads the real dictionary metadata (5571)
      and is not a hardcoded constant.
- [ ] run tests - must pass before next task

### Task 3: CLI, model + metadata save, MSE report
- [ ] add `main(argv)` / `__main__`: parse args (`--dataset`, `--dict`,
      `--examples`, `--out-model`, `--out-meta`, `--epochs`, etc.), regenerate
      examples if needed, set seed, build model, train, print train/val MSE.
- [ ] save model weights to a `.pt` (`torch.save(state_dict)`) and a metadata
      JSON containing `W`, `num_patterns`, `dense_size`, and the layer shapes.
- [ ] write test: after a 1-epoch `main` run on a tiny dataset, the `.pt` and
      metadata JSON exist and metadata has W/num_patterns/dense_size/layer shapes.
- [ ] run tests - must pass before next task

### Task 4: Verify acceptance criteria
- [ ] run full suite: `python3 -m unittest discover -s python/v2 -p "*_test.py"`.
- [ ] run end-to-end: `python3 python/v2/train_v2.py` (default few epochs) on the
      real 1048-example corpus; confirm it regenerates examples, trains, prints
      train/val MSE, and writes the `.pt` + metadata JSON.
- [ ] confirm num_patterns is read from metadata (grep: no literal 5571 in code).

## Technical Details

- EmbeddingBag input format: flat `ids` (LongTensor), `offsets` (LongTensor,
  one per example marking each bag's start), `per_sample_weights` (FloatTensor,
  the counts, same length as `ids`). Empty bag: consecutive equal offsets.
- Save: `torch.save(model.state_dict(), out_model)` and `json.dump({...}, ...)`.
- Keep default epochs small (e.g. 15-30) so iteration stays fast on CPU.

## Post-Completion

*Informational only — no checkboxes.*

- Real game-outcome WDL + larger multi-size corpus arrive via bead d4a.3.4; the
  training script must re-run unchanged on that corpus (only `--dataset` differs).
- Quantized/SIMD/C++ inference is deferred to a later optimization bead.
