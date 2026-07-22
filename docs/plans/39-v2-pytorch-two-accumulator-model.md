# Implementation Plan - PyTorch Two-Accumulator Model for NNUE v2 (nnue-trainer-d4a.2.1)

> **CRITICAL REQUIREMENT**: All Pull Requests for this task MUST target the `v2` branch, NOT `master`.

## Issue & Bead
Bead: `nnue-trainer-d4a.2.1` (under Epic `nnue-trainer-d4a.2` / `nnue-trainer-d4a`).

## Objective
Implement the float-first PyTorch NNUE v2 reference model (`python/v2/nnue_v2_model.py` and `python/v2/train_v2.py`) consuming counted sparse pattern IDs (STM and NSTM accumulators) + 14 dense manual features.

## Architecture Specification
1. **Inputs**:
   - `sparse_stm`: Sparse counted pattern IDs for Side-To-Move.
   - `sparse_nstm`: Sparse counted pattern IDs for Non-Side-To-Move.
   - `dense14`: 14 dense manual floats from `dense_features.py`.
2. **First Layer (Accumulators)**:
   - `EmbeddingBag` / linear weight lookup for sparse pattern IDs into `Acc_STM` (dim 1024) and `Acc_NSTM` (dim 1024) with ReLU activation.
3. **Dense Hidden Layers**:
   - Concatenate `[Acc_STM, Acc_NSTM, Dense14]` (total input size 2062).
   - Linear(2062 -> 32) -> ReLU -> Linear(32 -> 1) -> Sigmoid / WDL loss (win=1.0, draw=0.5, loss=0.0).
4. **Export Format**:
   - Save weights & architecture config as `nnue_v2_weights.json`.

## Files to Create
- `python/v2/nnue_v2_model.py`: PyTorch Module definition for NNUE v2.
- `python/v2/train_v2.py`: Training & validation script.
- `python/v2/test_model.py`: Unit test asserting forward pass shapes, gradient flow, and loss convergence on synthetic inputs.

## Verification Command
```bash
python3 -m unittest discover -s python/v2 -p "*_test.py"
```
