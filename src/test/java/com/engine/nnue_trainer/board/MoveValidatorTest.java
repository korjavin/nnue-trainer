package com.engine.nnue_trainer.board;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

public class MoveValidatorTest {

  private Board createConnectedBoard() {
    Board board = new Board(5, 5);
    // Set up player 1 base and normal cells to create connection to (2, 2)'s neighborhood
    board.setCell(0, 0, new Cell(1, CellKind.BASE));
    board.setCell(1, 1, new Cell(1, CellKind.NORMAL));
    return board;
  }

  @Test
  public void testOutOfBounds() {
    Board board = createConnectedBoard();
    assertFalse(MoveValidator.isValidMove(1, new Pos(-1, 0), board));
    assertFalse(MoveValidator.isValidMove(1, new Pos(0, -1), board));
    assertFalse(MoveValidator.isValidMove(1, new Pos(5, 0), board));
    assertFalse(MoveValidator.isValidMove(1, new Pos(0, 5), board));
  }

  @Test
  public void testValidEmptyCell() {
    Board board = createConnectedBoard();
    // Pos (2, 2) is adjacent to connected (1, 1), so it should be valid
    assertTrue(MoveValidator.isValidMove(1, new Pos(2, 2), board));
  }

  @Test
  public void testValidEnemyNormalCell() {
    Board board = createConnectedBoard();
    board.setCell(2, 2, new Cell(2, CellKind.NORMAL)); // Enemy player 2
    assertTrue(MoveValidator.isValidMove(1, new Pos(2, 2), board));
  }

  @Test
  public void testInvalidOwnNormalCell() {
    Board board = createConnectedBoard();
    board.setCell(2, 2, new Cell(1, CellKind.NORMAL)); // Own player 1
    assertFalse(MoveValidator.isValidMove(1, new Pos(2, 2), board));
  }

  @Test
  public void testInvalidBaseCell() {
    Board board = createConnectedBoard();
    board.setCell(2, 2, new Cell(2, CellKind.BASE)); // Enemy base
    assertFalse(MoveValidator.isValidMove(1, new Pos(2, 2), board));
  }

  @Test
  public void testInvalidFortifiedCell() {
    Board board = createConnectedBoard();
    board.setCell(2, 2, new Cell(2, CellKind.FORTIFIED));
    assertFalse(MoveValidator.isValidMove(1, new Pos(2, 2), board));
  }

  @Test
  public void testInvalidNeutralCell() {
    Board board = createConnectedBoard();
    board.setCell(2, 2, new Cell(0, CellKind.NEUTRAL));
    assertFalse(MoveValidator.isValidMove(1, new Pos(2, 2), board));
  }

  @Test
  public void testNonAdjacentCell() {
    Board board = createConnectedBoard();
    // (1, 1) is connected. (3, 3) is not adjacent (it's 2 spaces away diagonally)
    assertFalse(MoveValidator.isValidMove(1, new Pos(3, 3), board));

    // (1, 1) is connected. (0, 3) is not adjacent.
    assertFalse(MoveValidator.isValidMove(1, new Pos(0, 3), board));
  }

  @Test
  public void testCaptureEnemyBaseAdjacent() {
    Board board = createConnectedBoard(); // P1 has connected base at (0,0) and normal at (1,1)

    // Enemy base at (2, 2) which is adjacent to (1, 1)
    board.setCell(2, 2, new Cell(2, CellKind.BASE));

    // Player 1 can capture adjacent enemy base? Actually MoveValidator says:
    // targetCell.kind != CellKind.NORMAL -> returns false.
    // Wait, let's verify MoveValidator implementation.
    // Ah, MoveValidator:
    // if (targetCell.kind != CellKind.EMPTY) {
    //   if (targetCell.kind != CellKind.NORMAL || targetCell.owner == player) {
    //     return false;
    //   }
    // }
    // Thus it is FALSE for CellKind.BASE! So you can never capture a base directly with a normal
    // move?
    // Let me test this expected behavior based on the current implementation.
    assertFalse(MoveValidator.isValidMove(1, new Pos(2, 2), board));
  }

  @Test
  public void testCaptureEnemyBaseNonAdjacent() {
    Board board = createConnectedBoard();
    board.setCell(4, 4, new Cell(2, CellKind.BASE));

    // Not adjacent to (1, 1)
    assertFalse(MoveValidator.isValidMove(1, new Pos(4, 4), board));
  }
}
