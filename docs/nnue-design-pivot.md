# NNUE Design Pivot: Whole-Board Inputs

This document records the architectural decision to pivot away from Go's 26 handcrafted features towards a direct, whole-board representation for the NNUE model.

## 1. Motivation
The previous Go-based approach computed 26 handcrafted features per player (such as articulation points, max cut loss, front openness, Voronoi reach, etc.). These features are computationally expensive to calculate at search leaf nodes, and in practice, they failed to yield sufficient playing strength or generalize well.

Instead of hand-designing features, we will feed the entire board state directly into the NNUE model, allowing the first hidden layer to learn spatial, connectivity, and tactical patterns automatically.

## 2. Input Representation & Encoding (1v1 Perspective Optimization)
To maximize training efficiency and reduce parameter space, the network is restricted to **1v1 play** and uses a **perspective-based mapping** (evaluating the board relative to the active player whose turn it is to move):

- **"Us" (active player)** is mapped to Player index `1` in the vector encoding.
- **"Them" (opponent)** is mapped to Player index `2` in the vector encoding.

### Cell State Mapping
Since bases at `(0,0)` and `(11,11)` are static and guaranteed to be alive during all active search evaluations, we omit "base" states from the cell encoding entirely. Each cell on the board has one of **6 possible states**:
1. `0`: Empty (and static bases)
2. `1`: Normal piece owned by Us (active player)
3. `2`: Normal piece owned by Them (opponent)
4. `3`: Fortified piece owned by Us (active player)
5. `4`: Fortified piece owned by Them (opponent)
6. `5`: Neutral cell

### Feature Vector Dimension
For the fixed 12x12 board size:
$$\text{Input Size} = 144 \text{ cells} \times 6 \text{ states} = 864 \text{ features}$$

This provides a **57% reduction** in network input size and parameter space compared to the general 14-state model, dramatically speeding up PyTorch training iterations and minimax leaf node forward evaluations.

## 3. NNUE Model Architecture Adjustments
- **Input Layer**: 864 float values (instead of 2016).
- **Hidden Layer**: 256 nodes (Clipped ReLU activation).
- **Output Layer**: 1 scalar evaluation score.

## 4. Execution Plan
1. **Bead 1**: Update NNUEModel and BoardFeatureMapper to support 864 features using perspective-based mapping and 6 cell states.
2. **Bead 2**: Adapt the self-play simulation data generator to generate dataset records matching the 864-feature perspective-based format.
