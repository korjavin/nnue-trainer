# Implementation Plan - NNUE v2 Reference Pattern Accumulator Full Recompute (nnue-trainer-d4a.1.1)

> **CRITICAL REQUIREMENT**: All Pull Requests for this task MUST target the `v2` branch, NOT `master`.

## Issue & Bead
Bead: `nnue-trainer-d4a.1.1` (under Epic `nnue-trainer-d4a.1` / `nnue-trainer-d4a`).

## Objective
Implement the reference runtime accumulator path for NNUE v2 using the 5x5 pattern signature contract and exact promoted pattern dictionary.

## Design Specifications
1. Read a `Board` configuration of variable size (e.g. 5x5, 8x8, 12x12).
2. Scan active 5x5 windows and extract pattern IDs for STM and NSTM perspectives.
3. Ignore pattern dictionary misses (unseen patterns during inference).
4. Accumulate first-layer weights into two accumulator buffers: `Accumulator_STM` (size K, e.g. 1024) and `Accumulator_NSTM` (size K).
5. Output full concatenated feature representation `[Accumulator_STM, Accumulator_NSTM, Dense14]`.

## Files to Create
- `python/v2/accumulator.py`: Python reference accumulator.
- `src/main/java/com/engine/nnue_trainer/v2/NNUEv2Accumulator.java`: Java reference accumulator.
- `src/test/java/com/engine/nnue_trainer/v2/NNUEv2AccumulatorTest.java`: Unit tests verifying deterministic full recompute and handling of variable board sizes.

## Verification Command
```bash
./mvnw test -Dtest=NNUEv2AccumulatorTest
```
