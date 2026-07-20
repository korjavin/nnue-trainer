# Game Loop Messages Handler

Implement game loop handlers for WebSocket messaging in `com.engine.nnue_trainer.protocol`.

## Tasks

- [ ] Task 1: Handle multiplayer game loop events

### Task 1: Handle multiplayer game loop events
1. Parse `multiplayer_game_start`: store assigned player index, grid size (`rows`, `cols`).
2. Parse `turn_change`: if `player` matches our bot index, make a move.
3. Parse `move_made`: update local board representation.
4. Parse `game_end`: reset game state and return to idle.\n