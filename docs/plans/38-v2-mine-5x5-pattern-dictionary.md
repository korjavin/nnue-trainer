# Implementation Plan - Mine Promoted 5x5 Pattern Dictionary for NNUE v2 (nnue-trainer-d4a.3.2)

> **CRITICAL REQUIREMENT**: All Pull Requests for this task MUST target the `v2` branch, NOT `master`.

## Issue & Bead
Bead: `nnue-trainer-d4a.3.2` (under Epic `nnue-trainer-d4a.3` / `nnue-trainer-d4a`).

## Objective
Build the offline pattern mining pipeline (`python/v2/mine_patterns.py`) to scan self-play positions using the 5x5 signature contract (`pattern_contract.py`), count occurrence frequencies, and export an exact promoted pattern dictionary (`nnue_v2_dictionary.json`) mapping canonical pattern signatures to stable feature IDs (2k to 10k patterns).

## Design Specifications
1. **Input**: Self-play game JSON files.
2. **Frequency Filtering**:
   - Count occurrence of active 5x5 pattern signatures.
   - Ignore 100% empty / out-of-bounds windows.
   - Promote patterns meeting minimum count threshold `min_count` (e.g. >= 5).
3. **Dictionary Export Artifact (`nnue_v2_dictionary.json`)**:
   - `pattern_to_id`: Map of canonical pattern signature string -> integer feature ID `0..N-1`.
   - `metadata`: `num_patterns`, `min_count`, `version: 2`.
4. **Verification**:
   - Deterministic dictionary generation given identical input positions.

## Files to Create
- `python/v2/mine_patterns.py`: Offline pattern mining script.
- `src/main/java/com/engine/nnue_trainer/v2/PatternDictionary.java`: Java loader & lookup for promoted dictionary.
- `src/test/java/com/engine/nnue_trainer/v2/PatternDictionaryTest.java`: Unit test for dictionary loading, lookup, and miss handling.

## Verification Command
```bash
./mvnw test -Dtest=PatternDictionaryTest
python3 -m unittest discover -s python/v2 -p "*_test.py"
```
