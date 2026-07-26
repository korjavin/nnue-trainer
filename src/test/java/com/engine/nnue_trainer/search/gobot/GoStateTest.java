package com.engine.nnue_trainer.search.gobot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import org.junit.jupiter.api.Test;

/** Task 1: canonical size-general terminal outcome used to label self-play positions. */
public class GoStateTest {

  private static Board basesOnly(int rows, int cols) {
    Board b = new Board(rows, cols);
    b.setCell(0, 0, new Cell(1, CellKind.BASE));
    b.setCell(rows - 1, cols - 1, new Cell(2, CellKind.BASE));
    return b;
  }

  private static int outcome(Board b) {
    return GoState.fromBoard(b, 1, GoState.ACTIONS_PER_TURN, new boolean[4]).outcomeWinner();
  }

  @Test
  public void territoryDecidesFullBoardWithBothBasesAlive() {
    // Full 5x5 of FORTIFIED cells (nobody can move) with both bases intact; p1 owns more cells.
    Board b = basesOnly(5, 5);
    for (int r = 0; r < 5; r++) {
      for (int c = 0; c < 5; c++) {
        if ((r == 0 && c == 0) || (r == 4 && c == 4)) {
          continue; // keep the bases
        }
        int owner = r <= 2 ? 1 : 2; // upper rows to p1 → p1 has the territory majority
        b.setCell(r, c, new Cell(owner, CellKind.FORTIFIED));
      }
    }
    assertEquals(1, outcome(b), "territory majority wins even with both bases alive");
  }

  @Test
  public void equalTerritoryIsAGenuineTie() {
    // Full 5x5, both stuck, split 12/12 with one neutral cell → equal territory → 0 (wdl 0.5).
    Board b = basesOnly(5, 5);
    int p1 = 1; // base already owned by p1
    int p2 = 1; // base already owned by p2
    for (int r = 0; r < 5; r++) {
      for (int c = 0; c < 5; c++) {
        if ((r == 0 && c == 0) || (r == 4 && c == 4)) {
          continue;
        }
        if (r == 2 && c == 2) {
          b.setCell(r, c, new Cell(0, CellKind.NEUTRAL)); // the odd cell out
          continue;
        }
        int owner = p1 <= p2 ? 1 : 2;
        b.setCell(r, c, new Cell(owner, CellKind.FORTIFIED));
        if (owner == 1) {
          p1++;
        } else {
          p2++;
        }
      }
    }
    assertEquals(12, p1);
    assertEquals(12, p2);
    assertEquals(0, outcome(b), "equal territory is a genuine tie");
  }

  @Test
  public void aDestroyedBaseNeverWinsOnTerritory() {
    // p2's base is gone (inactive) but its cells stay on the board and outnumber p1's; p1 is alive
    // yet stuck (walled in by fortified cells nobody can take). Base destruction stays decisive.
    Board b = new Board(3, 3);
    b.setCell(0, 0, new Cell(1, CellKind.BASE));
    b.setCell(0, 1, new Cell(2, CellKind.FORTIFIED));
    b.setCell(1, 0, new Cell(2, CellKind.FORTIFIED));
    b.setCell(1, 1, new Cell(2, CellKind.FORTIFIED));
    b.setCell(0, 2, new Cell(2, CellKind.FORTIFIED));
    b.setCell(1, 2, new Cell(2, CellKind.FORTIFIED));
    b.setCell(2, 0, new Cell(2, CellKind.FORTIFIED));
    b.setCell(2, 1, new Cell(2, CellKind.FORTIFIED));
    b.setCell(2, 2, new Cell(2, CellKind.FORTIFIED));
    assertEquals(1, outcome(b), "an eliminated player's territory must not win the game");
  }

  @Test
  public void boardSnapshotHelperMatchesTheInstanceRule() {
    Board b = basesOnly(5, 5);
    assertEquals(
        GoState.fromBoard(b, 1, GoState.ACTIONS_PER_TURN, new boolean[2]).outcomeWinner(),
        GoState.outcomeWinner(b, 1));
  }

  @Test
  public void destroyedBaseMakesTheSurvivorWin() {
    // p2 has no base (eliminated); p1 base intact with room to move → p1 wins.
    Board b = new Board(5, 5);
    b.setCell(0, 0, new Cell(1, CellKind.BASE));
    assertEquals(1, outcome(b), "a lost base makes a player inactive, so the survivor wins");
  }
}
