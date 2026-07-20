# Integrate NNUE Evaluation in Search Engine

Connect the NNUEModel forward pass to the search leaf nodes.

## Tasks

- [ ] Task 1: Implement feature vector extraction and evaluate leaf nodes using NNUEModel

### Task 1: Implement feature vector extraction and evaluate leaf nodes using NNUEModel
1. Integrate feature vector extraction (`float[] extractFeatures(Board board)`) from `nnue` package.
2. In `SearchEngine.java`, when evaluating leaf nodes (depth == 0), retrieve features for the current board state.
3. Feed features into `NNUEModel.forward()` to get the scalar score.
4. Scale or normalize the score and return it as the minimax evaluation value.\n