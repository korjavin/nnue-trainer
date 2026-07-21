package com.engine.nnue_trainer.board;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

public class BaseConnectionSearchTest {

  @Test
  public void testP1Connected() {
    Board board = new Board(5, 5);
    board.setCell(0, 0, new Cell(1, CellKind.BASE));
    board.setCell(0, 1, new Cell(1, CellKind.NORMAL));
    board.setCell(1, 1, new Cell(1, CellKind.NORMAL));
    board.setCell(2, 2, new Cell(1, CellKind.NORMAL)); // diagonal connection

    // P1 isolated cell
    board.setCell(4, 4, new Cell(1, CellKind.NORMAL));

    // P2 cell
    board.setCell(0, 2, new Cell(2, CellKind.NORMAL));

    boolean[] connected = BaseConnectionSearch.connected(1, board);

    assertTrue(connected[0 * 5 + 0]);
    assertTrue(connected[0 * 5 + 1]);
    assertTrue(connected[1 * 5 + 1]);
    assertTrue(connected[2 * 5 + 2]);

    assertFalse(connected[4 * 5 + 4]); // not connected
    assertFalse(connected[0 * 5 + 2]); // P2 cell
    assertFalse(connected[3 * 5 + 3]); // empty cell
  }

  @Test
  public void testP2Connected() {
    Board board = new Board(3, 3);
    board.setCell(2, 2, new Cell(2, CellKind.BASE));
    board.setCell(2, 1, new Cell(2, CellKind.NORMAL));

    // Isolated
    board.setCell(0, 0, new Cell(2, CellKind.NORMAL));

    boolean[] connected = BaseConnectionSearch.connected(2, board);

    assertTrue(connected[2 * 3 + 2]);
    assertTrue(connected[2 * 3 + 1]);
    assertFalse(connected[0 * 3 + 0]);
  }

  @Test
  public void testP3Connected() {
    Board board = new Board(4, 4);
    board.setCell(0, 3, new Cell(3, CellKind.BASE));
    board.setCell(1, 3, new Cell(3, CellKind.NORMAL));
    board.setCell(1, 2, new Cell(3, CellKind.NORMAL));

    boolean[] connected = BaseConnectionSearch.connected(3, board);

    assertTrue(connected[0 * 4 + 3]);
    assertTrue(connected[1 * 4 + 3]);
    assertTrue(connected[1 * 4 + 2]);
    assertFalse(connected[0 * 4 + 0]);
  }

  @Test
  public void testP4Connected() {
    Board board = new Board(4, 4);
    board.setCell(3, 0, new Cell(4, CellKind.BASE));
    board.setCell(2, 1, new Cell(4, CellKind.NORMAL));
    board.setCell(1, 2, new Cell(4, CellKind.NORMAL));

    boolean[] connected = BaseConnectionSearch.connected(4, board);

    assertTrue(connected[3 * 4 + 0]);
    assertTrue(connected[2 * 4 + 1]);
    assertTrue(connected[1 * 4 + 2]);
    assertFalse(connected[0 * 4 + 3]);
  }

  @Test
  public void testNoBase() {
    Board board = new Board(3, 3);
    // P1 base is at 0, 0 but it's not owned by P1
    board.setCell(0, 0, new Cell(2, CellKind.BASE));
    board.setCell(0, 1, new Cell(1, CellKind.NORMAL));

    boolean[] connected = BaseConnectionSearch.connected(1, board);
    assertFalse(connected[0 * 3 + 0]);
    assertFalse(connected[0 * 3 + 1]);
  }

  @Test
  public void testDiagonalConnectivity() {
    Board board = new Board(5, 5);
    board.setCell(0, 0, new Cell(1, CellKind.BASE));
    board.setCell(1, 1, new Cell(1, CellKind.NORMAL));
    board.setCell(2, 2, new Cell(1, CellKind.NORMAL));
    board.setCell(3, 3, new Cell(1, CellKind.NORMAL));

    boolean[] connected = BaseConnectionSearch.connected(1, board);

    assertTrue(connected[0 * 5 + 0]);
    assertTrue(connected[1 * 5 + 1]);
    assertTrue(connected[2 * 5 + 2]);
    assertTrue(connected[3 * 5 + 3]);
  }

  @Test
  public void testDisconnectionByNeutralCells() {
    Board board = new Board(5, 5);
    board.setCell(0, 0, new Cell(1, CellKind.BASE));
    board.setCell(0, 1, new Cell(1, CellKind.NORMAL));
    // Cut off by neutral cell
    board.setCell(0, 2, new Cell(0, CellKind.NEUTRAL));
    board.setCell(0, 3, new Cell(1, CellKind.NORMAL));

    boolean[] connected = BaseConnectionSearch.connected(1, board);

    assertTrue(connected[0 * 5 + 0]);
    assertTrue(connected[0 * 5 + 1]);
    assertFalse(connected[0 * 5 + 2]);
    assertFalse(connected[0 * 5 + 3]); // Disconnected
  }

  @Test
  public void testDisconnectionByOpponentCells() {
    Board board = new Board(5, 5);
    board.setCell(0, 0, new Cell(1, CellKind.BASE));
    board.setCell(1, 0, new Cell(1, CellKind.NORMAL));
    // Cut off by opponent cell
    board.setCell(2, 0, new Cell(2, CellKind.NORMAL));
    board.setCell(3, 0, new Cell(1, CellKind.NORMAL));

    boolean[] connected = BaseConnectionSearch.connected(1, board);

    assertTrue(connected[0 * 5 + 0]);
    assertTrue(connected[1 * 5 + 0]);
    assertFalse(connected[2 * 5 + 0]);
    assertFalse(connected[3 * 5 + 0]); // Disconnected
  }

  @Test
  public void testEmptyBaseConnection() {
    Board board = new Board(5, 5);
    // Base is empty
    board.setCell(0, 0, new Cell(0, CellKind.EMPTY));
    board.setCell(0, 1, new Cell(1, CellKind.NORMAL));
    board.setCell(1, 1, new Cell(1, CellKind.NORMAL));

    boolean[] connected = BaseConnectionSearch.connected(1, board);

    assertFalse(connected[0 * 5 + 0]);
    assertFalse(connected[0 * 5 + 1]);
    assertFalse(connected[1 * 5 + 1]);
  }
}
