# Implementation Plan - Extract Sparse Counted Training Examples with STM WDL Labels (nnue-trainer-d4a.3.3)

> **CRITICAL REQUIREMENT**: All Pull Requests for this task MUST target the `v2` branch, NOT `master`.

## Issue & Bead
Bead: `nnue-trainer-d4a.3.3` (under Epic `nnue-trainer-d4a.3` / `nnue-trainer-d4a`).

## Objective
Implement the training data extractor for NNUE v2. For each sampled board position, emit counted sparse pattern IDs for STM and NSTM perspectives using `nnue_v2_dictionary.json`, attach the 14 dense manual features (`dense_features.py`), and label WDL targets (win 1.0, draw 0.5, loss 0.0) from the Side-To-Move perspective.

## Design Specifications
1. **Input**: Self-play game JSON files.
2. **Output Dataset Format (`nnue_v2_dataset.json`)**:
   - `sparse_stm`: List of `[pattern_id, count]` pairs.
   - `sparse_nstm`: List of `[pattern_id, count]` pairs.
   - `dense14`: List of 14 normalized float features.
   - `wdl_target`: Float `1.0` (win), `0.5` (draw), `0.0` (loss) relative to active player (`stm`).
   - `board_size`: `[rows, cols]`.
3. **Deterministic Sampling**: Fixed random seed for position sub-sampling.

## Files to Create
- `python/v2/extract_v2_dataset.py`: Dataset extraction script.
- `src/main/java/com/engine/nnue_trainer/v2/NNUEv2DatasetExtractor.java`: Java dataset exporter.
- `src/test/java/com/engine/nnue_trainer/v2/NNUEv2DatasetExtractorTest.java`: Unit tests verifying dataset record format and target perspective flipping.

## Verification Command
```bash
./mvnw test -Dtest=NNUEv2DatasetExtractorTest
python3 -m unittest discover -s python/v2 -p "*_test.py"
```
