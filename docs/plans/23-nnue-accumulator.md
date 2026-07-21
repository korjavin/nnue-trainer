# Plan: Efficient NNUE Accumulator (Incremental Evaluation)

This plan specifies the implementation of an efficient, updatable accumulator layer in the NNUE inference pipeline to speed up search node evaluations.

## 1. Goal
Instead of mapping all 144 cells and running a full $[256 \times 864]$ matrix multiplication on every leaf node evaluation, the engine will maintain an accumulator (the input/hidden layer output) that updates incrementally. When a move is played, we only apply the diff (subtracting the weights of the old cell state and adding the weights of the new cell state).

## 2. Requirements

### 2.1 Accumulator Class (`Accumulator.java`)
Create `src/main/java/com/engine/nnue_trainer/nnue/Accumulator.java`:
- Holds the raw pre-activation sums for the 256 hidden layer nodes:
  `private final float[] Accum` of size 256.
- Implement an initialization method:
  `public void init(Board board, int perspectivePlayer, NNUEModel model)`:
  - Computes the full pre-activation sum from scratch using `BoardFeatureMapper` and `model.getHiddenWeights()` / `model.getHiddenBiases()`.
- Implement an incremental update method:
  `public void update(int row, int col, int oldState, int newState, NNUEModel model)`:
  - For each hidden node `i` from 0 to 255:
    - If `oldState` was mapped to a valid feature index `f_old`:
      - Subtract `hiddenWeights[i][f_old]` from `Accum[i]`.
    - If `newState` is mapped to a valid feature index `f_new`:
      - Add `hiddenWeights[i][f_new]` to `Accum[i]`.
- Implement a forward pass method:
  `public float[] getClippedReLUActivation()`:
  - Applies Clipped ReLU (`Math.max(0.0f, Math.min(127.0f, Accum[i]))`) to return the 256-node activation array.

### 2.2 Integration in SearchEngine
- Add support in `SearchEngine` to use `Accumulator` if available, or update the board representation to support incremental updates.
- Note: If full integration in search requires recursive copy, ensure that a fallback is provided so that if the board is evaluated directly, the accumulator can be computed.

### 2.3 Verification & Testing
- Create a unit test `AccumulatorTest` that compares the outputs of:
  1. An accumulator initialized from scratch.
  2. An accumulator updated incrementally after a move.
  - Assert that both produce identical output vectors.
- Run spotless formatting check.
