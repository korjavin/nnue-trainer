# Board and Coordinates System

Implement the basic board representation classes in the package `com.engine.nnue_trainer.board`.

## Tasks

- [ ] Task 1: Create core game board representation classes

### Task 1: Create core game board representation classes
1. Create `Pos.java` representing a grid position with `row` and `col` fields.
2. Create `CellKind.java` enum representing:
   - `EMPTY` (0)
   - `NORMAL` (1)
   - `BASE` (2)
   - `FORTIFIED` (3)
   - `NEUTRAL` (4)
3. Create `Cell.java` with fields: `owner` (int representing player 1-4, or 0 for none) and `kind` (`CellKind`).
4. Create `Board.java` representing the grid with board size (`rows`, `cols`), an array of cells, and helper methods.\n