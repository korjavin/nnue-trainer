package com.engine.nnue_trainer.nnue;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import org.junit.jupiter.api.Test;

public class BoardFeatureMapperTest {

  @Test
  public void testMappingPerspectivePlayer1() {
    Board board = new Board(12, 12);

    board.setCell(0, 0, new Cell(0, CellKind.EMPTY));
    board.setCell(0, 1, new Cell(1, CellKind.NORMAL));
    board.setCell(0, 2, new Cell(2, CellKind.NORMAL));
    board.setCell(0, 3, new Cell(1, CellKind.FORTIFIED));
    board.setCell(0, 4, new Cell(2, CellKind.FORTIFIED));
    board.setCell(0, 5, new Cell(0, CellKind.NEUTRAL));
    board.setCell(0, 6, new Cell(1, CellKind.BASE)); // Base acts as empty in current mapping

    float[] features = BoardFeatureMapper.map(board, 1);

    assertEquals(864, features.length);

    // Empty cell -> 0
    int idx00 = 0 * 12 + 0;
    assertEquals(1.0f, features[idx00 * 6 + 0]);

    // Normal Us -> 1
    int idx01 = 0 * 12 + 1;
    assertEquals(1.0f, features[idx01 * 6 + 1]);

    // Normal Them -> 2
    int idx02 = 0 * 12 + 2;
    assertEquals(1.0f, features[idx02 * 6 + 2]);

    // Fortified Us -> 3
    int idx03 = 0 * 12 + 3;
    assertEquals(1.0f, features[idx03 * 6 + 3]);

    // Fortified Them -> 4
    int idx04 = 0 * 12 + 4;
    assertEquals(1.0f, features[idx04 * 6 + 4]);

    // Neutral -> 5
    int idx05 = 0 * 12 + 5;
    assertEquals(1.0f, features[idx05 * 6 + 5]);

    // Base -> 0 (unmapped)
    int idx06 = 0 * 12 + 6;
    assertEquals(1.0f, features[idx06 * 6 + 0]);
  }

  @Test
  public void testMappingPerspectivePlayer2() {
    Board board = new Board(12, 12);

    board.setCell(0, 1, new Cell(1, CellKind.NORMAL));
    board.setCell(0, 2, new Cell(2, CellKind.NORMAL));
    board.setCell(0, 3, new Cell(1, CellKind.FORTIFIED));
    board.setCell(0, 4, new Cell(2, CellKind.FORTIFIED));

    float[] features = BoardFeatureMapper.map(board, 2);

    assertEquals(864, features.length);

    // Normal Them (Player 1) -> 2
    int idx01 = 0 * 12 + 1;
    assertEquals(1.0f, features[idx01 * 6 + 2]);

    // Normal Us (Player 2) -> 1
    int idx02 = 0 * 12 + 2;
    assertEquals(1.0f, features[idx02 * 6 + 1]);

    // Fortified Them (Player 1) -> 4
    int idx03 = 0 * 12 + 3;
    assertEquals(1.0f, features[idx03 * 6 + 4]);

    // Fortified Us (Player 2) -> 3
    int idx04 = 0 * 12 + 4;
    assertEquals(1.0f, features[idx04 * 6 + 3]);
  }
}
