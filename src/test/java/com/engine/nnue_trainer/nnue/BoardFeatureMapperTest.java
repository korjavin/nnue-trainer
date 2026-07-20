package com.engine.nnue_trainer.nnue;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import org.junit.jupiter.api.Test;

public class BoardFeatureMapperTest {

  @Test
  public void testMappingPerspectiveP1() {
    Board board = new Board(12, 12);

    board.setCell(0, 0, new Cell(0, CellKind.EMPTY));
    board.setCell(0, 1, new Cell(1, CellKind.NORMAL)); // Us
    board.setCell(0, 2, new Cell(2, CellKind.NORMAL)); // Them
    board.setCell(0, 3, new Cell(1, CellKind.FORTIFIED)); // Us fortified
    board.setCell(0, 4, new Cell(2, CellKind.FORTIFIED)); // Them fortified
    board.setCell(0, 5, new Cell(1, CellKind.BASE)); // Us base
    board.setCell(0, 6, new Cell(2, CellKind.BASE)); // Them base
    board.setCell(0, 7, new Cell(0, CellKind.NEUTRAL)); // Neutral

    float[] features = BoardFeatureMapper.map(board, 1);

    assertEquals(1152, features.length);

    // Empty cell (index 0)
    int cellIndex00 = 0 * 12 + 0;
    assertEquals(1.0f, features[cellIndex00 * 8 + 0]);

    // Us normal (index 1)
    int cellIndex01 = 0 * 12 + 1;
    assertEquals(1.0f, features[cellIndex01 * 8 + 1]);

    // Them normal (index 2)
    int cellIndex02 = 0 * 12 + 2;
    assertEquals(1.0f, features[cellIndex02 * 8 + 2]);

    // Us fortified (index 3)
    int cellIndex03 = 0 * 12 + 3;
    assertEquals(1.0f, features[cellIndex03 * 8 + 3]);

    // Them fortified (index 4)
    int cellIndex04 = 0 * 12 + 4;
    assertEquals(1.0f, features[cellIndex04 * 8 + 4]);

    // Us base (index 5)
    int cellIndex05 = 0 * 12 + 5;
    assertEquals(1.0f, features[cellIndex05 * 8 + 5]);

    // Them base (index 6)
    int cellIndex06 = 0 * 12 + 6;
    assertEquals(1.0f, features[cellIndex06 * 8 + 6]);

    // Neutral (index 7)
    int cellIndex07 = 0 * 12 + 7;
    assertEquals(1.0f, features[cellIndex07 * 8 + 7]);

    // Assert that a missing state is indeed 0
    assertEquals(0.0f, features[cellIndex07 * 8 + 0]);
  }

  @Test
  public void testMappingPerspectiveP2() {
    Board board = new Board(12, 12);

    board.setCell(0, 0, new Cell(0, CellKind.EMPTY));
    board.setCell(0, 1, new Cell(1, CellKind.NORMAL)); // Them
    board.setCell(0, 2, new Cell(2, CellKind.NORMAL)); // Us
    board.setCell(0, 3, new Cell(1, CellKind.FORTIFIED)); // Them fortified
    board.setCell(0, 4, new Cell(2, CellKind.FORTIFIED)); // Us fortified
    board.setCell(0, 5, new Cell(1, CellKind.BASE)); // Them base
    board.setCell(0, 6, new Cell(2, CellKind.BASE)); // Us base
    board.setCell(0, 7, new Cell(0, CellKind.NEUTRAL)); // Neutral

    float[] features = BoardFeatureMapper.map(board, 2);

    assertEquals(1152, features.length);

    // Empty cell (index 0)
    int cellIndex00 = 0 * 12 + 0;
    assertEquals(1.0f, features[cellIndex00 * 8 + 0]);

    // Them normal (index 2)
    int cellIndex01 = 0 * 12 + 1;
    assertEquals(1.0f, features[cellIndex01 * 8 + 2]);

    // Us normal (index 1)
    int cellIndex02 = 0 * 12 + 2;
    assertEquals(1.0f, features[cellIndex02 * 8 + 1]);

    // Them fortified (index 4)
    int cellIndex03 = 0 * 12 + 3;
    assertEquals(1.0f, features[cellIndex03 * 8 + 4]);

    // Us fortified (index 3)
    int cellIndex04 = 0 * 12 + 4;
    assertEquals(1.0f, features[cellIndex04 * 8 + 3]);

    // Them base (index 6)
    int cellIndex05 = 0 * 12 + 5;
    assertEquals(1.0f, features[cellIndex05 * 8 + 6]);

    // Us base (index 5)
    int cellIndex06 = 0 * 12 + 6;
    assertEquals(1.0f, features[cellIndex06 * 8 + 5]);

    // Neutral (index 7)
    int cellIndex07 = 0 * 12 + 7;
    assertEquals(1.0f, features[cellIndex07 * 8 + 7]);

    // Assert that a missing state is indeed 0
    assertEquals(0.0f, features[cellIndex07 * 8 + 0]);
  }
}
