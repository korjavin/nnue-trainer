# Plan: Self-Play Simulation Data Generator

This plan specifies the implementation of a self-play generator in Java to create dataset records for NNUE training.

## 1. Goal
Create a standalone Java class `SelfPlayGenerator` that simulates games between two search engine bot instances, gathers board snapshots with their active player perspective, and exports the dataset to a JSON file.

## 2. Requirements

### 2.1 Self-Play Generator Class (`SelfPlayGenerator.java`)
Create `src/main/java/com/engine/nnue_trainer/train/SelfPlayGenerator.java`:
- Add a `main` method that can be executed directly.
- The generator must simulate a configurable number of games (e.g. 50 games by default).
- For each game:
  - Initialize a $12 \times 12$ board (Base 1 at `(0,0)`, Base 2 at `(11,11)`).
  - Alternate turns between Player 1 and Player 2.
  - On each player's turn:
    - Get all legal actions using `MoveGenerator.getLegalActions(player, board, canPlaceNeutral)`.
    - Run minimax search to score actions.
    - **Exploration (Soft-Max or Epsilon-Greedy)**: To ensure diverse training positions, do not always pick the best move:
      - With $10\%$ probability (epsilon = 0.1), pick a random legal action.
      - With $90\%$ probability, pick the best action returned by the search engine.
    - Apply the chosen action to the board.
    - Collect the board state and active player index at this turn.
    - If the game ends or reaches 100 turns (draw limit), stop.
  - Determine the final winner of the game (Player 1 or Player 2, or 0 for draw).
  - For each collected turn in the game:
    - Map the board snapshot to the perspective-based 864-feature vector using `BoardFeatureMapper.map(board, activePlayer)`.
    - Set target value to `1.0` if active player won, `-1.0` if active player lost, and `0.0` for draw.
- Export all collected positions to a JSON file `src/main/resources/self_play_data.json` at the end of the run.

### 2.2 Verification & Execution
- Include a simple unit test to verify that `SelfPlayGenerator` runs one full simulated game successfully.
- Run spotless formatting check.
