# Plan: Board Rules Validation Edge-Case Unit Tests

This plan specifies the implementation of thorough unit tests to validate the correctness of game rules (MoveValidator, MoveGenerator, and BaseConnectionSearch) under complex edge cases.

## 1. Goal
Ensure zero regressions in game rule validation by implementing robust unit tests covering complex layouts, diagonals, neutral blocks, base capturing conditions, and connectivity searches.

## 2. Requirements

### 2.1 BaseConnectionSearch Unit Tests (`BaseConnectionSearchTest.java`)
Add test cases in `src/test/java/com/engine/nnue_trainer/board/BaseConnectionSearchTest.java` (create if not exists):
- **Diagonal Connectivity**: Verify that cells connected only diagonally to the base are correctly flagged as connected.
- **Disconnection by Neutral Cells**: Verify that if a path of owned cells is cut off/split by a neutral cell, the cells past the cut are correctly marked as disconnected.
- **Disconnection by Opponent Cells**: Verify that if opponent cells cut the path, the trailing cells are marked as disconnected.
- **Empty Base Connection**: Verify that if a player's base is empty or occupied by the opponent, no cells are connected for that player.

### 2.2 MoveValidator Edge-Case Unit Tests (`MoveValidatorTest.java`)
Add test cases in `src/test/java/com/engine/nnue_trainer/board/MoveValidatorTest.java` (create if not exists):
- **Adjacency Constraint**: Verify that a player cannot place a normal piece on a cell that is not adjacent (including diagonals) to one of their already connected pieces.
- **Capture Enemy Base**: Verify that a player can only capture the enemy's base if they have an active connected piece adjacent to it.
- **Neutral Capture Restriction**: Verify that neutral cells cannot be placed or modified by normal moves.
- **Invalid Position Bounds**: Verify that out-of-bounds positions return false.

### 2.3 MoveGenerator Edge-Case Unit Tests (`MoveGeneratorTest.java`)
Add test cases in `src/test/java/com/engine/nnue_trainer/board/MoveGeneratorTest.java` (create if not exists):
- **Initial Moves**: Verify that at turn 1, the only legal moves generated are adjacent to the player's base.
- **Neutral Placement Generation**: Verify that placing two neutrals is only generated when `canPlaceNeutral` is true, and it produces all unique pairs of the player's own normal pieces.
- **No Moves Condition**: Verify that when a player has no pieces and their base is captured, the generator returns an empty list.

### 2.4 Verification
- Run spotless formatting check.
- Verify that all unit tests pass successfully.
