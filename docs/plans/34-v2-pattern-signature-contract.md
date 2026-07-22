# Implementation Plan - NNUE v2 5x5 Pattern Signature Contract (nnue-trainer-d4a.3.1)

> **CRITICAL REQUIREMENT**: All Pull Requests for this task MUST target the `v2` branch, NOT `master`.

## Issue & Bead
Bead: `nnue-trainer-d4a.3.1` (under Epic `nnue-trainer-d4a.3` / `nnue-trainer-d4a`).

## Objective
Define and implement the core 5x5 pattern signature and canonicalization contract for NNUE v2.

## Design Specifications
1. **Alphabet (8 symbols)**:
   - `EMPTY = 0`
   - `NEUTRAL = 1`
   - `BASE_SELF = 2`
   - `BASE_OPPONENT = 3`
   - `NORMAL_SELF = 4`
   - `NORMAL_OPPONENT = 5`
   - `FORTIFIED_SELF = 6`
   - `FORTIFIED_OPPONENT = 7`
   - `OUT_OF_BOUNDS = 8`
2. **Active-Window Emission Rule**:
   - Only emit 5x5 windows centered at cells that contain non-empty / non-neutral pieces or active board cells.
   - Skip windows that are 100% empty/out-of-bounds.
3. **Perspective Normalization**:
   - Normalize cell ownership relative to Side-To-Move (STM) and Non-Side-To-Move (NSTM).
4. **Manhattan Distance Buckets**:
   - Compute normalized Manhattan distance to enemy base (bucketed 0..7).

## Files to Create
- `python/v2/pattern_contract.py`: Reference Python extractor and canonical signature builder.
- `src/main/java/com/engine/nnue_trainer/v2/PatternContract.java`: Java equivalent contract.
- `src/test/java/com/engine/nnue_trainer/v2/PatternContractTest.java`: Unit tests for active window emission, edge out-of-bounds handling, and perspective symmetry.

## Verification Command
```bash
./mvnw test -Dtest=PatternContractTest
python3 -m unittest discover -s python/v2 -p "*_test.py"
```
