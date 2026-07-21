# Plan: Upgrade Java SearchEngine to match GoBot Search Capabilities

This plan specifies the enhancements to `SearchEngine.java` to implement a stronger search (Zobrist transposition tables, move ordering, principal variation search, quiescence search, and opening book plies) to reach parity with GoBot.

## 1. Goal
Significantly improve search strength under a fixed NNUE evaluation model by implementing high-efficiency search techniques. Parity is verified via in-repo Java self-play A/B tests between the upgraded `SearchEngine` and the baseline version under equal time or node budgets.

## 2. Core Techniques to Implement

### 2.1 Move Ordering
Enhance current move ordering in `orderActions` / `scoreAction`:
- **Transposition Table Move First**: The best move retrieved from the transposition table (TT) for the current position must be searched first.
- **Killer Move Heuristic**: Track quiet moves that cause beta cutoffs at each depth level and sort them immediately after TT and capture moves.
- **History Heuristic**: Keep a history table counting how often quiet moves (originating cell and destination cell) caused beta cutoffs during search, and sort quiet moves by their history score.

### 2.2 Transposition Table (TT)
Implement a robust transposition table in the engine:
- **Zobrist Hashing**:
  - Pre-generate random 64-bit keys (longs) for every state of every cell: `(row, col, state)` where `state` covers Owner (None, Player 1, Player 2) and Kind (Normal, Base, Neutral).
  - Include a key for the side to move (Player 1 vs Player 2).
  - Maintain the Zobrist hash incrementally or compute it dynamically during search nodes (incremental updates via board changes are more efficient).
- **Table Storage**:
  - Use a fixed-size array of TT entries (e.g. power of 2 sized array, using hashing with key mapping to fit within memory).
  - Each entry stores: `zobristKey` (to resolve hash collisions), `bestAction` (the best move found from this node), `score` (evaluation value), `depth` (search depth of the evaluation), and `flag` (Exact, Alpha/Upper Bound, Beta/Lower Bound).
- **Search Integration**:
  - Look up the position in the TT at the start of `alphaBeta`. If a match is found with sufficient depth, use the score to prune or return immediately if possible, or use the stored `bestAction` to order moves first.
  - Store search results in the TT before returning from `alphaBeta`.

### 2.3 Principal Variation Search (PVS) / Negascout
Replace standard alpha-beta minimax with PVS:
- Search the first move (the principal variation or TT move) with a full window `(alpha, beta)`.
- For subsequent moves, search with a null window `(alpha, alpha + 1)` under the assumption that they will not improve the score.
- If the null-window search returns a score greater than `alpha` (revaluation), execute a full re-search with the window `(score, beta)`.

### 2.4 Quiescence Search
Mitigate the horizon effect by implementing a quiescence search:
- When depth reaches 0, instead of returning the static evaluation directly, enter `quiescenceSearch(board, alpha, beta, ...)`.
- Establish a "stand-pat" score (the static evaluation) as a lower bound. If the stand-pat score >= beta, return it. If it > alpha, update alpha.
- Only generate and search "loud" moves (e.g., moves that convert/capture opponent normal pieces or threaten/capture opponent bases).
- Recursively perform quiescence search on these loud moves to resolve tactical captures before doing static evaluations.

### 2.5 Opening Book / Seeded Random Openings
Avoid deterministic repetition of identical games in self-play:
- Implement a seeded opening randomizer or a small opening library.
- For the first 2-4 plies of a game, select moves randomly from legal options (using a configurable random seed for deterministic verification runs). This forces path diversity in self-play evaluation runs.

## 3. Verification & Metrics
- **A/B Testing**:
  - Create/extend the in-repo Self-Play Generator or write a dedicated sparring test class that plays a set of games (e.g., 50-100 games) pitting the new upgraded search engine against the baseline search engine under equal time-limit (e.g., 200ms or 500ms per move).
  - Report the win rate of the new engine vs. the baseline.
- **Node Efficiency**:
  - Compare nodes evaluated at target depths (e.g., depth 4, 5, 6) with and without the upgrades. The upgraded engine should require significantly fewer nodes to search to the same depth.
- **Correctness Guard**:
  - Run correctness tests ensuring the TT does not introduce search instability (the best move and score at depth D should be identical to the search without TT).
  - Keep spotless checks passing.
