# Alpha-Beta Search Minimax Framework

Implement the search engine framework using Alpha-Beta Minimax search in package `com.engine.nnue_trainer.search`.

## Tasks

- [ ] Task 1: Create search framework with minimax and alpha-beta pruning

### Task 1: Create search framework with minimax and alpha-beta pruning
1. Create `SearchEngine.java`.
2. Implement alpha-beta minimax search:
   `float alphaBeta(Board board, int depth, float alpha, float beta, int player, boolean maximizingPlayer)`
3. For terminal nodes or leaf nodes (depth == 0), return the evaluation score (initially a simple count of pieces, or stub evaluation).
4. For active player, maximize the score; for opponents, minimize it.
5. Create basic unit tests verifying simple minimax decisions (e.g. choose winning move if available).\n