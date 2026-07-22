# Implementation Plan - NNUE v2 Incremental Changed-Window Accumulator Updates (nnue-trainer-d4a.1.2)

> **CRITICAL REQUIREMENT**: All Pull Requests for this task MUST target the `v2` branch, NOT `master`.

## Issue & Bead
Bead: `nnue-trainer-d4a.1.2` (under Epic `nnue-trainer-d4a.1` / `nnue-trainer-d4a`).

## Objective
Implement fast incremental accumulator updates for NNUE v2. For each move, identify the union of all 5x5 windows overlapping modified cells, subtract old pattern occurrences, add new pattern occurrences, and assert exact output parity vs full recompute.

## Design Specifications
1. **Affected Window Identification**:
   - A single cell modification at `(r, c)` affects 5x5 windows centered at `(r + dr, c + dc)` for `dr, dc in [-2, 2]` (up to 25 candidate windows).
   - For multi-cell moves (e.g. `PlaceNeutralsAction` modifying 2 cells), construct the set union of unique 5x5 window centers to avoid duplicate subtraction/addition.
2. **Incremental Accumulation Update**:
   - For each unique affected 5x5 window:
     - Extract old 5x5 pattern signature -> lookup old pattern ID -> subtract feature vector column from `Acc_STM` and `Acc_NSTM`.
     - Extract new 5x5 pattern signature -> lookup new pattern ID -> add feature vector column to `Acc_STM` and `Acc_NSTM`.
3. **Parity Guarantee**:
   - Assert that `Acc_incremental.equals(Acc_full_recompute)` across 100 random move sequences.

## Files to Create
- `python/v2/incremental_accumulator.py`: Python incremental accumulator.
- `src/main/java/com/engine/nnue_trainer/v2/NNUEv2IncrementalAccumulator.java`: Java incremental accumulator implementation.
- `src/test/java/com/engine/nnue_trainer/v2/NNUEv2IncrementalAccumulatorTest.java`: Unit tests verifying exact match between incremental updates and full recomputation.

## Verification Command
```bash
./mvnw test -Dtest=NNUEv2IncrementalAccumulatorTest
python3 -m unittest discover -s python/v2 -p "*_test.py"
```
