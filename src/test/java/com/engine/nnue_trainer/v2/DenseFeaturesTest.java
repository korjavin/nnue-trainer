package com.engine.nnue_trainer.v2;

import static org.junit.jupiter.api.Assertions.*;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import org.junit.jupiter.api.Test;

class DenseFeaturesTest {

  @Test
  void testBoundsAndSymmetry() {
    Board board = new Board(12, 12);
    board.setCell(0, 0, new Cell(1, CellKind.BASE));
    board.setCell(11, 11, new Cell(2, CellKind.BASE));
    board.setCell(0, 1, new Cell(1, CellKind.NORMAL));
    board.setCell(11, 10, new Cell(2, CellKind.NORMAL));

    float[] f1 = DenseFeatures.extract(board, 1, 10);
    float[] f2 = DenseFeatures.extract(board, 2, 10);

    assertEquals(14, f1.length);
    assertEquals(14, f2.length);

    for (int i = 0; i < 14; i++) {
      assertTrue(f1[i] >= 0.0f && f1[i] <= 1.0f, "Feature " + i + " out of bounds");
      assertTrue(f2[i] >= 0.0f && f2[i] <= 1.0f, "Feature " + i + " out of bounds");
    }

    // Symmetry check
    assertEquals(f1[0], f2[1], 1e-6); // stm normal
    assertEquals(f1[1], f2[0], 1e-6); // nstm normal
    assertEquals(f1[2], f2[3], 1e-6); // stm fortified
    assertEquals(f1[3], f2[2], 1e-6); // nstm fortified
    assertEquals(f1[4], f2[4], 1e-6); // neutral
    assertEquals(f1[5], f2[5], 1e-6); // empty
    assertEquals(f1[6], f2[7], 1e-6); // stm base
    assertEquals(f1[7], f2[6], 1e-6); // nstm base
    assertEquals(f1[8], f2[9], 1e-6); // stm dist
    assertEquals(f1[9], f2[8], 1e-6); // nstm dist
    assertEquals(f1[10], f2[11], 1e-6); // stm components
    assertEquals(f1[11], f2[10], 1e-6); // nstm components
    assertEquals(f1[12], f2[12], 1e-6); // turn
    assertEquals(f1[13], f2[13], 1e-6); // board size
  }
}
