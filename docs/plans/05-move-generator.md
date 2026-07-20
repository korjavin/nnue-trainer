# Move and Action Generator

Implement move generation logic, including neutral placements.

## Tasks

- [ ] Task 1: Implement action generation for normal moves and neutral placements

### Task 1: Implement action generation for normal moves and neutral placements
1. Create `Action.java` to represent actions:
   - Move (grow or attack target position).
   - PlaceNeutrals (placing two neutral fields on two own Normal cells, replacing the turn).
2. Implement `List<Action> getLegalActions(int player, Board board, boolean canPlaceNeutral)` that:
   - Generates all valid Move actions (positions adjacent to connected territory).
   - If `canPlaceNeutral` is true and it's the start of the turn (3 moves remaining), generate all unique pairs of the player's own Normal cells to place neutrals.\n