# Implementation Plan - NNUE v2 14 Dense Manual Features Encoder (nnue-trainer-d4a.2.2)

> **CRITICAL REQUIREMENT**: All Pull Requests for this task MUST target the `v2` branch, NOT `master`.

## Issue & Bead
Bead: `nnue-trainer-d4a.2.2` (under Epic `nnue-trainer-d4a.2` / `nnue-trainer-d4a`).

## Objective
Implement and document the 14 dense manual features extractor to be concatenated alongside sparse pattern accumulators in NNUE v2.

## Dense Features Specification (14 floats)
1. `stm_normal_count_norm` (normalized by total board area)
2. `nstm_normal_count_norm`
3. `stm_fortified_count_norm`
4. `nstm_fortified_count_norm`
5. `neutral_count_norm`
6. `empty_count_norm`
7. `stm_base_alive` (1.0 or 0.0)
8. `nstm_base_alive` (1.0 or 0.0)
9. `stm_min_dist_to_enemy_base_norm` (Manhattan distance / max_dist)
10. `nstm_min_dist_to_enemy_base_norm`
11. `stm_connected_components_ratio`
12. `nstm_connected_components_ratio`
13. `turn_number_norm` (turn / 100.0)
14. `board_size_norm` (rows * cols / 144.0)

## Files to Create
- `python/v2/dense_features.py`: Python 14-feature extractor.
- `src/main/java/com/engine/nnue_trainer/v2/DenseFeatures.java`: Java equivalent feature extractor.
- `src/test/java/com/engine/nnue_trainer/v2/DenseFeaturesTest.java`: Unit tests asserting feature bounds [0.0, 1.0] and symmetry.

## Verification Command
```bash
./mvnw test -Dtest=DenseFeaturesTest
```
