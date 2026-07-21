# Plan: User-Friendly Logging and Search Diagnostics

This plan specifies the clean-up of websocket message logging and the implementation of search engine diagnostics in the console.

## 1. Goal
Reduce websocket log noise by replacing full raw JSON printouts with concise, human-readable notifications. Add search statistics (depth, evaluation score, nodes evaluated, and elapsed time) to help analyze the bot's decisions in real-time.

## 2. Requirements

### 2.1 Suppress Raw Message Logging (`BotWebSocketClient.java`)
- Modify `src/main/java/com/engine/nnue_trainer/protocol/BotWebSocketClient.java`:
  - Remove or comment out `System.out.println("Received message: " + message);` in the `onMessage` callback to prevent raw JSON dumps from cluttering the terminal.

### 2.2 User-Friendly Game Loop Messages (`GameLoopHandler.java`)
- Modify `src/main/java/com/engine/nnue_trainer/protocol/GameLoopHandler.java`:
  - When parsing `move_made` or `neutrals_placed`, print a clean message only if it belongs to the opponent (to avoid duplicating printouts since the bot already prints its own actions when sending them):
    - Example: `System.out.println("Opponent played Move at (" + row + ", " + col + ")")`
  - When parsing `turn_change`, print a concise turn status:
    - Example: `System.out.println("Turn changed: Player " + currentPlayer + "'s turn (Moves left: " + movesLeft + ")")`

### 2.3 Search Engine Diagnostics (`SearchEngine.java`)
- Modify `src/main/java/com/engine/nnue_trainer/search/SearchEngine.java`:
  - Add thread-safe or static counters to track search statistics:
    - `private static int nodesEvaluated = 0;` (increment inside `evaluate` method).
  - In `findBestAction(Board board, int player, int depth, boolean canPlaceNeutral)`:
    - Record start time: `long startTime = System.currentTimeMillis();`
    - Reset `nodesEvaluated = 0;`
    - Execute the search loop.
    - Record end time and calculate elapsed time.
    - Print a clean diagnostics block:
      ```
      === Search Diagnostics ===
      Search Depth: 3
      Nodes Evaluated: <count>
      Time Elapsed: <ms> ms
      Best Action: <action-description>
      Position Evaluation: <float-score>
      ==========================
      ```

### 2.4 Verification
- Run spotless formatting check.
- Verify that tests compile and pass.
