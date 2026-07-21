# Plan: Outgoing Move Diagnostics Payload

This plan specifies the implementation of sending search diagnostics (score, depth, nodes evaluated, elapsed time) inside the websocket move JSON payload.

## 1. Goal
Provide the game server with real-time diagnostics of the bot's decisions. The bot will serialize its internal minimax scores and search statistics directly into the outgoing `"move"` and `"neutrals"` JSON messages.

## 2. Requirements

### 2.1 Refactor Search Engine Return Value (`SearchResult.java`)
- Create a new container class `SearchResult.java` under `com.engine.nnue_trainer.search`:
  ```java
  package com.engine.nnue_trainer.search;

  import com.engine.nnue_trainer.board.Action;

  public class SearchResult {
      public final Action bestAction;
      public final float score;
      public final int depth;
      public final int nodesEvaluated;
      public final long timeMs;

      public SearchResult(Action bestAction, float score, int depth, int nodesEvaluated, long timeMs) {
          this.bestAction = bestAction;
          this.score = score;
          this.depth = depth;
          this.nodesEvaluated = nodesEvaluated;
          this.timeMs = timeMs;
      }
  }
  ```
- Update `SearchEngine.java` to return `SearchResult` instead of a plain `Action`:
  - `findBestActionWithTimeLimit` must return a `SearchResult` containing the best action, its evaluation score, the maximum depth reached, the total nodes evaluated, and the elapsed search time in milliseconds.

### 2.2 Serialize Diagnostics in Outgoing Messages (`GameLoopHandler.java`)
- Update the `makeMove` method in `GameLoopHandler.java`:
  - Call `SearchEngine.findBestActionWithTimeLimit`.
  - In the outgoing `ObjectNode response` JSON payload, append the following fields:
    - `"score"`: `result.score` (float)
    - `"depth"`: `result.depth` (int)
    - `"nodesEvaluated"`: `result.nodesEvaluated` (int)
    - `"timeMs"`: `result.timeMs` (long)
  - Ensure that this additional metadata is cleanly serialized and sent via the websocket client.

### 2.3 Verification & Testing
- Update unit tests in `SearchEngineTest.java` and `GameLoopHandlerTest.java` to match the new `SearchResult` signature.
- Run spotless formatting check.
- Verify that tests compile and pass.
