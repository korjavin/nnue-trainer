# Plan: NNUE Board Inference Pipeline

This plan specifies the implementation of the board feature mapping, NNUE model forward pass, and integration of the NNUE evaluation into the minimax search engine.

## 1. Goal
Implement the complete board-state feature mapping and NNUE inference pipeline to evaluate board leaf nodes in search.

## 2. Requirements

### 2.1 Board State Mapping (`BoardFeatureMapper.java`)
Create `src/main/java/com/engine/nnue_trainer/nnue/BoardFeatureMapper.java` to map a $12 \times 12$ board state to a $2,016$-dimensional float vector.
- **States mapping per cell (indices 0..13)**:
  1. `0`: Empty cell (`cell.kind == CellKind.EMPTY` or `cell == null`)
  2. `1..4`: Normal piece owned by Player 1..4 (`cell.kind == CellKind.NORMAL && cell.owner == 1..4`)
  3. `5..8`: Base owned by Player 1..4 (`cell.kind == CellKind.BASE && cell.owner == 1..4`)
  4. `9..12`: Fortified piece owned by Player 1..4 (`cell.kind == CellKind.FORTIFIED && cell.owner == 1..4`)
  5. `13`: Neutral cell (`cell.kind == CellKind.NEUTRAL`)
- **One-hot encoding**:
  - The vector size must be `12 * 12 * 14 = 2016` elements.
  - For cell at row `r` and column `c`, calculate its 1D index: `cellIndex = r * 12 + c`.
  - Set `vector[cellIndex * 14 + stateIndex] = 1.0f`.
  - All other elements must be `0.0f`.
  - The method signature must be: `public static float[] map(Board board)`.

### 2.2 NNUE Model Input Sizing (`NNUEModel.java`)
Modify `src/main/java/com/engine/nnue_trainer/nnue/NNUEModel.java`:
- Change `inputSize` from `104` to `2016`.
- Update `forward` parameter validation: throw `IllegalArgumentException` if input length is not `2016`.
- Keep the hidden layer size as `256` (or allow dynamic initialization), and keep the clipped ReLU activation (clipping between `0.0f` and `127.0f`).

### 2.3 Search Engine Integration
Add NNUE evaluation capability to the search engine. You can either:
- Update `SearchEngine.java` to support an optional `NNUEModel` instance (e.g. via a constructor or setter). If present, evaluate the leaf node using the model:
  ```java
  float[] features = BoardFeatureMapper.map(board);
  return nnueModel.forward(features);
  ```
- Or extend `SearchEngine` with a subclass `NnueSearchEngine` that overrides `evaluate`.
Prefer updating the base class or subclass cleanly so it is fully integrated.

### 2.4 Verification & Testing
- Create/update unit tests to ensure that all $14$ cell states are mapped to their correct indexes in the one-hot float vector.
- Verify `NNUEModel` forward pass compiles and runs tests successfully with the $2,016$ dimensional input vector.
- Run Spotless formatting check.
