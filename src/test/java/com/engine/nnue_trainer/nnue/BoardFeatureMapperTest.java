package com.engine.nnue_trainer.nnue;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import org.junit.jupiter.api.Test;

public class BoardFeatureMapperTest {

  @Test
  public void testMapping() {
    Board board = new Board(12, 12);

    board.setCell(0, 0, new Cell(0, CellKind.EMPTY));
    board.setCell(0, 1, new Cell(1, CellKind.NORMAL));
    board.setCell(0, 2, new Cell(2, CellKind.BASE));
    board.setCell(0, 3, new Cell(3, CellKind.FORTIFIED));
    board.setCell(0, 4, new Cell(0, CellKind.NEUTRAL));

    float[] features = BoardFeatureMapper.map(board);

    assertEquals(2016, features.length);

    // Empty cell (index 0)
    int cellIndex00 = 0 * 12 + 0;
    assertEquals(1.0f, features[cellIndex00 * 14 + 0]);

    // Normal piece Player 1 (index 1)
    int cellIndex01 = 0 * 12 + 1;
    assertEquals(1.0f, features[cellIndex01 * 14 + 1]);

    // Base Player 2 (index 4 + 2 = 6)
    int cellIndex02 = 0 * 12 + 2;
    assertEquals(1.0f, features[cellIndex02 * 14 + 6]);

    // Fortified Player 3 (index 8 + 3 = 11)
    int cellIndex03 = 0 * 12 + 3;
    assertEquals(1.0f, features[cellIndex03 * 14 + 11]);

    // Neutral cell (index 13)
    int cellIndex04 = 0 * 12 + 4;
    assertEquals(1.0f, features[cellIndex04 * 14 + 13]);

    // Assert that a missing state is indeed 0
    assertEquals(0.0f, features[cellIndex04 * 14 + 0]);
  }
}
