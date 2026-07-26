package com.engine.nnue_trainer.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.v2.PatternContract;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Active-feature extraction for the v3 runtime leaf. */
public class NNUEv3AccumulatorTest {

  private static Board populated12() {
    Board b = new Board(NNUEv3Accumulator.BOARD, NNUEv3Accumulator.BOARD);
    b.setCell(0, 0, new Cell(1, CellKind.BASE));
    b.setCell(11, 11, new Cell(2, CellKind.BASE));
    b.setCell(3, 4, new Cell(1, CellKind.NORMAL));
    b.setCell(5, 5, new Cell(2, CellKind.FORTIFIED));
    b.setCell(7, 2, new Cell(0, CellKind.NEUTRAL));
    return b;
  }

  @Test
  public void testExactlyOneInRangeFeaturePerCell() {
    int[] active = NNUEv3Accumulator.activeFeatures(populated12(), 1);

    assertEquals(144, active.length);
    for (int i = 0; i < active.length; i++) {
      assertTrue(
          active[i] >= 0 && active[i] < NNUEv3Accumulator.FEATURES, "out of range: " + active[i]);
      // Row-major cell order, one state per cell: index i must land in cell i's 8-slot block.
      assertEquals(i, active[i] / NNUEv3Accumulator.STATES, "feature " + i + " left its cell");
    }
    assertEquals(144, Arrays.stream(active).distinct().count());
  }

  @Test
  public void testPerspectiveSymmetry() {
    Board b = populated12();
    int[] p1 = NNUEv3Accumulator.activeFeatures(b, 1);
    int[] p2 = NNUEv3Accumulator.activeFeatures(b, 2);

    // A cell owned by player 1 is *_SELF from stm=1 and *_OPPONENT from stm=2.
    int normal = 3 * NNUEv3Accumulator.BOARD + 4;
    assertEquals(NNUEv3Accumulator.idx(3, 4, PatternContract.NORMAL_SELF), p1[normal]);
    assertEquals(NNUEv3Accumulator.idx(3, 4, PatternContract.NORMAL_OPPONENT), p2[normal]);

    int fort = 5 * NNUEv3Accumulator.BOARD + 5;
    assertEquals(NNUEv3Accumulator.idx(5, 5, PatternContract.FORTIFIED_OPPONENT), p1[fort]);
    assertEquals(NNUEv3Accumulator.idx(5, 5, PatternContract.FORTIFIED_SELF), p2[fort]);

    // Owner-free states are perspective-invariant; owned ones all flip.
    assertEquals(
        NNUEv3Accumulator.idx(7, 2, PatternContract.NEUTRAL), p1[7 * NNUEv3Accumulator.BOARD + 2]);
    assertEquals(p1[7 * NNUEv3Accumulator.BOARD + 2], p2[7 * NNUEv3Accumulator.BOARD + 2]);
    assertEquals(p1[6 * NNUEv3Accumulator.BOARD + 6], p2[6 * NNUEv3Accumulator.BOARD + 6]);
  }

  @Test
  public void testRejectsNon12x12() {
    assertThrows(
        IllegalArgumentException.class, () -> NNUEv3Accumulator.activeFeatures(new Board(9, 9), 1));
    // Exact, not ">=": a bigger board must not be silently mined as its top-left 12x12.
    assertThrows(
        IllegalArgumentException.class,
        () -> NNUEv3Accumulator.activeFeatures(new Board(13, 13), 1));
  }
}
