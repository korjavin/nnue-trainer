# Plan: NNUE Board Feature Mapper

This plan specifies the implementation of a board feature mapper that converts a `Board` state into a 2,016-dimensional direct input vector (based on a 12x12 grid with 14 states per cell).

## 1. Goal
Implement `BoardFeatureMapper.java` in package `com.engine.nnue_trainer.nnue` and its unit tests in `BoardFeatureMapperTest.java`.

## 2. Requirements

### 2.1 State Encoding
For each cell at `(row, col)`, determine its state index (`0..13`):
1. `0`: Empty (`cell.kind == CellKind.EMPTY` or `cell == null`)
2. `1..4`: Normal piece owned by Player 1..4 (`cell.kind == CellKind.NORMAL && cell.owner == 1..4`)
3. `5..8`: Base owned by Player 1..4 (`cell.kind == CellKind.BASE && cell.owner == 1..4`)
4. `9..12`: Fortified piece owned by Player 1..4 (`cell.kind == CellKind.FORTIFIED && cell.owner == 1..4`)
5. `13`: Neutral cell (`cell.kind == CellKind.NEUTRAL`)

### 2.2 Output Representation
- The method `public static float[] map(Board board)` must return a `float[]` of size `board.rows * board.cols * 14`.
- For each cell at `(r, c)`, calculate `cellIndex = r * board.cols + c`.
- Set `output[cellIndex * 14 + stateIndex] = 1.0f`.
- All other values in the array must be `0.0f`.

### 2.3 Files to Create
- `src/main/java/com/engine/nnue_trainer/nnue/BoardFeatureMapper.java`
- `src/test/java/com/engine/nnue_trainer/nnue/BoardFeatureMapperTest.java`

## 3. Verification
- Verify that a 12x12 board maps to a 2,016 float array.
- Verify correct one-hot encoding for each cell state type.
- Run format checks using Spotless.
