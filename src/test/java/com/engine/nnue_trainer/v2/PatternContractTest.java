package com.engine.nnue_trainer.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import java.util.List;
import org.junit.jupiter.api.Test;

public class PatternContractTest {

  @Test
  public void testPerspectiveNormalization() {
    assertEquals(PatternContract.EMPTY, PatternContract.getSymbol(new Cell(0, CellKind.EMPTY), 1));
    assertEquals(
        PatternContract.NEUTRAL, PatternContract.getSymbol(new Cell(0, CellKind.NEUTRAL), 1));

    assertEquals(
        PatternContract.BASE_SELF, PatternContract.getSymbol(new Cell(1, CellKind.BASE), 1));
    assertEquals(
        PatternContract.BASE_OPPONENT, PatternContract.getSymbol(new Cell(2, CellKind.BASE), 1));

    assertEquals(
        PatternContract.NORMAL_SELF, PatternContract.getSymbol(new Cell(1, CellKind.NORMAL), 1));
    assertEquals(
        PatternContract.NORMAL_OPPONENT,
        PatternContract.getSymbol(new Cell(2, CellKind.NORMAL), 1));

    assertEquals(
        PatternContract.FORTIFIED_SELF,
        PatternContract.getSymbol(new Cell(2, CellKind.FORTIFIED), 2));
    assertEquals(
        PatternContract.FORTIFIED_OPPONENT,
        PatternContract.getSymbol(new Cell(1, CellKind.FORTIFIED), 2));

    assertEquals(PatternContract.OUT_OF_BOUNDS, PatternContract.getSymbol(null, 1));
  }

  @Test
  public void testDistanceBucket() {
    Board board = new Board(10, 10);
    board.setCell(9, 9, new Cell(2, CellKind.BASE)); // Enemy base for player 1

    // Ensure (0,0) window is emitted by putting a piece nearby
    board.setCell(0, 0, new Cell(1, CellKind.NORMAL));

    List<PatternContract.Window> windows = PatternContract.extractWindows(board, 1);

    // Find the window centered at (0, 0) - distance is 18, so bucket should be 7
    PatternContract.Window w00 =
        windows.stream().filter(w -> w.centerRow == 0 && w.centerCol == 0).findFirst().orElse(null);
    assertNotNull(w00);
    assertEquals(7, w00.distanceBucket);

    // Find the window centered at (8, 8) - distance is 2, so bucket should be 2
    PatternContract.Window w88 =
        windows.stream().filter(w -> w.centerRow == 8 && w.centerCol == 8).findFirst().orElse(null);
    assertNotNull(w88);
    assertEquals(2, w88.distanceBucket);
  }

  @Test
  public void testActiveWindowEmission() {
    Board board = new Board(5, 5);
    // Only one cell has a piece, everything else is empty
    board.setCell(2, 2, new Cell(1, CellKind.NORMAL));

    List<PatternContract.Window> windows = PatternContract.extractWindows(board, 1);

    // Windows should be emitted if they are not 100% empty/out-of-bounds.
    // A piece at (2,2) will appear in 5x5 windows centered at any r in [0, 4] and c in [0, 4]
    // since the maximum distance from center is 2.
    assertEquals(25, windows.size());

    // Now move the piece to 0,0
    board = new Board(5, 5);
    board.setCell(0, 0, new Cell(1, CellKind.NORMAL));

    windows = PatternContract.extractWindows(board, 1);
    // The piece at (0,0) is visible from centers r in [0, 2] and c in [0, 2] => 9 windows
    assertEquals(9, windows.size());
  }

  @Test
  public void testEdgeOutOfBoundsHandling() {
    Board board = new Board(3, 3);
    board.setCell(0, 0, new Cell(1, CellKind.NORMAL)); // Emit for center (0,0)

    List<PatternContract.Window> windows = PatternContract.extractWindows(board, 1);

    PatternContract.Window w00 =
        windows.stream().filter(w -> w.centerRow == 0 && w.centerCol == 0).findFirst().orElse(null);
    assertNotNull(w00);

    int[] symbols = w00.symbols;
    // Top-left of 5x5 centered at 0,0 is (-2, -2) -> OOB
    assertEquals(PatternContract.OUT_OF_BOUNDS, symbols[0]);
    // Center is (0,0) -> 12th index (index 12: row 2, col 2 in 0-4 mapping => 2*5+2 = 12)
    assertEquals(PatternContract.NORMAL_SELF, symbols[12]);
    // Bottom-right is (2,2) -> row 4, col 4 in 0-4 mapping => index 24. Since board is 3x3, (2,2)
    // is in bounds. It's EMPTY.
    assertEquals(PatternContract.EMPTY, symbols[24]);

    // (0,3) is OOB
    assertEquals(PatternContract.OUT_OF_BOUNDS, symbols[15]);
  }
}
