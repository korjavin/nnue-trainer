# Plan: Search Engine Move Ordering & Pruning

This plan specifies the implementation of move ordering in the search engine to maximize the efficiency of alpha-beta pruning.

## 1. Goal
Sort legal moves dynamically in `SearchEngine.java` before exploring them in `alphaBeta` search. Placing highly promising moves (like captures and checks) first leads to earlier alpha-beta cutoffs, significantly reducing search node count and speed.

## 2. Requirements

### 2.1 Move Scoring & Sorting (`SearchEngine.java`)
Implement a helper method `protected List<Action> orderActions(List<Action> actions, Board board, int player)` in `SearchEngine.java`:
- For each `Action` in `actions`, assign a heuristic score:
  - **Highest Priority (Score: 10000)**: Any move that directly captures/destroys the opponent's base (wins the game).
  - **High Priority (Score: 1000)**: Any move that captures an opponent cell (converts an opponent normal piece to our normal piece).
  - **Medium Priority (Score: 100)**: Any move that lands adjacent to the opponent's base (aggressive expansion/threat).
  - **Low Priority (Score: 0)**: Standard cell placements or neutral placements.
- Sort the `actions` list in descending order of their scores.

### 2.2 Integration in Search Loop
Update the search loop in `generateNextBoards` or the `alphaBeta` search loop:
- Sort actions using `orderActions` before generating child boards.
- In `generateNextBoards(Board board, int player, boolean maximizingPlayer)`:
  - Call `MoveGenerator.getLegalActions(player, board, false)`.
  - Sort the actions list using `orderActions(actions, board, player)`.
  - Apply the sorted actions to generate and return the child boards.
- In `findBestAction(Board board, int player, int depth, boolean canPlaceNeutral)`:
  - Get legal actions.
  - Sort the actions list using `orderActions(actions, board, player)`.
  - Evaluate child boards in the sorted order.

### 2.3 Verification & Testing
- Create a unit test `SearchEngineTest.testMoveOrdering` that asserts that a capturing move is sorted before a non-capturing move.
- Run spotless formatting check.
