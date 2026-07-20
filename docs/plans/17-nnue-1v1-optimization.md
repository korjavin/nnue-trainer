# Plan: NNUE 1v1 Perspective-Based Optimization

This plan details the pivot to 1v1 play with perspective-based 864-feature vector mapping.

## 1. Goal
Optimize the NNUE inference pipeline for 1v1 games by reducing input dimension from 2016 to 864 using perspective-based cell state mapping.

## 2. Requirements

### 2.1 Perspective-Based Feature Mapping (`BoardFeatureMapper.java`)
Update `src/main/java/com/engine/nnue_trainer/nnue/BoardFeatureMapper.java`:
- Change method signature to accept the active player: `public static float[] map(Board board, int activePlayer)`
- Vector size is fixed at `144 * 6 = 864` elements.
- For each cell, determine the state index `0..5` relative to the `activePlayer` (where opponent is `3 - activePlayer` for 1v1):
  1. `0`: Empty, base cell, or any other unmapped state.
  2. `1`: Normal piece owned by Us (`cell.kind == CellKind.NORMAL && cell.owner == activePlayer`).
  3. `2`: Normal piece owned by Them (`cell.kind == CellKind.NORMAL && cell.owner == opponent`).
  4. `3`: Fortified piece owned by Us (`cell.kind == CellKind.FORTIFIED && cell.owner == activePlayer`).
  5. `4`: Fortified piece owned by Them (`cell.kind == CellKind.FORTIFIED && cell.owner == opponent`).
  6. `5`: Neutral cell (`cell.kind == CellKind.NEUTRAL`).
- Calculate 1D cell index `cellIndex = r * 12 + c` and set:
  `vector[cellIndex * 6 + stateIndex] = 1.0f`.

### 2.2 Model Input Resize (`NNUEModel.java`)
Update `src/main/java/com/engine/nnue_trainer/nnue/NNUEModel.java`:
- Change `inputSize` check from `2016` to `864`.
- Validate input array length in `forward`: throw `IllegalArgumentException` if input length is not `864`.

### 2.3 Search Engine Perspective Integration (`SearchEngine.java`)
Update `SearchEngine.java`'s `evaluate` method:
- When calling `BoardFeatureMapper.map(board, activePlayer)`, pass the maximizing player as the `activePlayer` perspective:
  ```java
  int activePlayer = maximizingPlayer ? player : getOpponent(player);
  float[] features = BoardFeatureMapper.map(board, activePlayer);
  return nnueModel.forward(features);
  ```

### 2.4 Tests Verification
- Update `BoardFeatureMapperTest` to assert that changing the perspective player (e.g. from 1 to 2) correctly swaps Us (`1`/`3`) and Them (`2`/`4`) states in the resulting feature vector.
- Update `NNUEModelTest` to assert on 864-sized input validation.
- Run spotless formatting check.
