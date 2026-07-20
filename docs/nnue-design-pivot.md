# NNUE Design Pivot: Whole-Board Inputs

This document records the architectural decision to pivot away from Go's 26 handcrafted features towards a direct, whole-board representation for the NNUE model.

## 1. Motivation
The previous Go-based approach computed 26 handcrafted features per player (such as articulation points, max cut loss, front openness, Voronoi reach, etc.). These features are computationally expensive to calculate at search leaf nodes, and in practice, they failed to yield sufficient playing strength or generalize well.

Instead of hand-designing features, we will feed the entire board state directly into the NNUE model, allowing the first hidden layer to learn spatial, connectivity, and tactical patterns automatically.

## 2. Input Representation & Encoding
We represent each cell of the grid as a one-hot vector of its state.

### State Mapping per Cell
A cell on the board can have one of **14 possible states**:
1. `0`: Empty
2. `1`: Normal piece owned by Player 1
3. `2`: Normal piece owned by Player 2
4. `3`: Normal piece owned by Player 3
5. `4`: Normal piece owned by Player 4
6. `5`: Base owned by Player 1
7. `6`: Base owned by Player 2
8. `7`: Base owned by Player 3
9. `8`: Base owned by Player 4
10. `9`: Fortified piece owned by Player 1
11. `10`: Fortified piece owned by Player 2
12. `11`: Fortified piece owned by Player 3
13. `12`: Fortified piece owned by Player 4
14. `13`: Neutral cell

### Feature Vector Dimension
For a default $N \times M$ grid, the input vector size is:
$$\text{Input Size} = (N \times M) \times 14$$

For the default 12x12 board size:
$$\text{Input Size} = 144 \times 14 = 2016 \text{ features}$$

This size (2,016 inputs) is extremely small compared to traditional chess NNUEs (which typically have 40,000+ features). It is highly performant and can be evaluated on a CPU in microseconds.

## 3. NNUE Model Architecture Adjustments
- **Input Layer**: 2016 float values (instead of 104).
- **Hidden Layer**: 256 or 512 nodes (Clipped ReLU activation).
- **Output Layer**: 1 scalar evaluation score.

## 4. Execution Plan
We will file the following issues in the Beads database:
1. **Bead 1**: Update NNUE input dimensions and forward pass to support the 2016-dimensional whole-board feature vector.
2. **Bead 2**: Implement the whole-board feature mapper in Java (`com.engine.nnue_trainer.nnue`).
3. **Bead 3**: Adapt the self-play simulation data generator to produce dataset records containing full board states instead of handcrafted features.
