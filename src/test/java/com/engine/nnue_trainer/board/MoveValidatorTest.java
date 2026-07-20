package com.engine.nnue_trainer.board;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

public class MoveValidatorTest {

  private Board createBoardWithConnectionForP1(int targetRow, int targetCol) {
    Board board = new Board(5, 5);
    // P1 base is at (0, 0)
    board.setCell(0, 0, new Cell(1, CellKind.BASE));
    // Create a path of normal cells from (0,0) to a cell adjacent to target
    // We can just set all cells owned by P1 up to targetRow, targetCol-1 or similar.
    for (int r = 0; r <= targetRow; r++) {
      for (int c = 0; c <= targetCol; c++) {
        if (r == targetRow && c == targetCol) {
          continue; // Leave the target cell alone
        }
        board.setCell(r, c, new Cell(1, CellKind.NORMAL));
      }
    }
    return board;
  }

  @Test
  public void testOutOfBounds() {
    Board board = new Board(5, 5);
    assertFalse(MoveValidator.isValidMove(1, new Pos(-1, 0), board));
    assertFalse(MoveValidator.isValidMove(1, new Pos(0, -1), board));
    assertFalse(MoveValidator.isValidMove(1, new Pos(5, 0), board));
    assertFalse(MoveValidator.isValidMove(1, new Pos(0, 5), board));
  }

  @Test
  public void testValidEmptyCell() {
    Board board = createBoardWithConnectionForP1(2, 2);
    // (2,2) is EMPTY by default
    assertTrue(MoveValidator.isValidMove(1, new Pos(2, 2), board));
  }

  @Test
  public void testValidEnemyNormalCell() {
    Board board = createBoardWithConnectionForP1(2, 2);
    board.setCell(2, 2, new Cell(2, CellKind.NORMAL)); // Enemy player 2
    assertTrue(MoveValidator.isValidMove(1, new Pos(2, 2), board));
  }

  @Test
  public void testInvalidOwnNormalCell() {
    Board board = createBoardWithConnectionForP1(2, 2);
    board.setCell(2, 2, new Cell(1, CellKind.NORMAL)); // Own player 1
    assertFalse(MoveValidator.isValidMove(1, new Pos(2, 2), board));
  }

  @Test
  public void testInvalidBaseCell() {
    Board board = createBoardWithConnectionForP1(2, 2);
    board.setCell(2, 2, new Cell(2, CellKind.BASE)); // Enemy base
    assertFalse(MoveValidator.isValidMove(1, new Pos(2, 2), board));
  }

  @Test
  public void testInvalidFortifiedCell() {
    Board board = createBoardWithConnectionForP1(2, 2);
    board.setCell(2, 2, new Cell(2, CellKind.FORTIFIED));
    assertFalse(MoveValidator.isValidMove(1, new Pos(2, 2), board));
  }

  @Test
  public void testInvalidNeutralCell() {
    Board board = createBoardWithConnectionForP1(2, 2);
    board.setCell(2, 2, new Cell(0, CellKind.NEUTRAL));
    assertFalse(MoveValidator.isValidMove(1, new Pos(2, 2), board));
  }
}
