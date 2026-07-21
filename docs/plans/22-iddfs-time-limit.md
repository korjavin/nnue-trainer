# Plan: Iterative Deepening with Time-Bounded Search Control

This plan specifies the transition of the search engine from a fixed search depth to Iterative Deepening Depth-First Search (IDDFS) controlled by a configurable time limit per move.

## 1. Goal
Maximize the search depth dynamically based on available time. Instead of a hardcoded depth 3, the engine will search deeper (e.g. depth 5, 8, or 10) on fast positions, but abort safely and return the best fully calculated move if the time limit (e.g. 1000ms) is reached.

## 2. Requirements

### 2.1 Timeout Management
- Create a lightweight custom runtime exception `SearchTimeoutException` under `com.engine.nnue_trainer.search`.
- Update `alphaBeta` in `SearchEngine.java` to accept a `long startTime` and `long timeLimitMs` parameter.
- At the start of the `alphaBeta` call (or at every recursive node), check if the elapsed time exceeds the limit:
  ```java
  if (System.currentTimeMillis() - startTime >= timeLimitMs) {
      throw new SearchTimeoutException();
  }
  ```

### 2.2 Iterative Deepening Loop (`SearchEngine.java`)
- Implement a time-bounded search runner:
  `public static Action findBestActionWithTimeLimit(Board board, int player, long timeLimitMs, boolean canPlaceNeutral)`:
  - Initialize the search parameters.
  - Run an IDDFS loop starting from `depth = 1` up to a maximum safety ceiling (e.g. `20`):
    - Within a `try-catch` block catching `SearchTimeoutException`:
      - Execute minimax search at current `depth`.
      - If the search completes fully without timeout, update the `bestAction` variable.
    - If `SearchTimeoutException` is caught, break the loop and return the `bestAction` resolved at the last fully completed search depth.
- Update `GameLoopHandler.java` to call `SearchEngine.findBestActionWithTimeLimit(board, myPlayerIndex, 1000, canPlaceNeutral)` (using a default limit of 1000ms).

### 2.3 Verification & Testing
- Add a unit test verifying that if the search is given a very short time limit (e.g. 1ms), it aborts cleanly and returns a valid action from the shallowest completed depth without crashing.
- Run spotless formatting check.
