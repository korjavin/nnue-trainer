# Base Connection Search

Implement base connectivity checks in the package `com.engine.nnue_trainer.board`.

## Tasks

- [ ] Task 1: Implement connected cells search using BFS or DFS

### Task 1: Implement connected cells search using BFS or DFS
1. Each player starts with a base cell in one of the corners:
   - P1: (0, 0)
   - P2: (rows - 1, cols - 1)
   - P3: (0, cols - 1)
   - P4: (rows - 1, 0)
2. Implement a method `boolean[] connected(int player, Board board)` that does an 8-connected flood fill (BFS/DFS) starting from the player's base cell.
3. Only cells owned by the player are traversed.
4. Return a boolean mask corresponding to connected cells (cells connected back to the corner base).\n