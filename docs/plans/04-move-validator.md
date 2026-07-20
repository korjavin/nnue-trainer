# Move Validator

Implement move validation logic in `com.engine.nnue_trainer.board`.

## Tasks

- [ ] Task 1: Implement isValidMove validation logic

### Task 1: Implement isValidMove validation logic
1. Implement `boolean isValidMove(int player, Pos target, Board board)` which returns true if a player can legally move to `target`.
2. Valid target rules:
   - Target must be in bounds.
   - Target kind must be `EMPTY` or `NORMAL` owned by another player (cannot attack bases, fortified, neutral, or own normal cells).
   - Target must be 8-adjacent to at least one cell in the player's *connected* territory (precomputed via `connected(player, board)`).\n