package com.engine.nnue_trainer.search.gobot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.v3.NNUEv3Evaluator;
import com.engine.nnue_trainer.v3.V3TestEvaluators;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Task 4: the {@code LeafEval.NNUEV3} GoBot leaf — units, orientation, wiring, 12x12 fallback. */
public class GoBotNnueV3LeafTest {

  private static Board board(int size) {
    Board b = new Board(size, size);
    b.setCell(0, 0, new Cell(1, CellKind.BASE));
    b.setCell(size - 1, size - 1, new Cell(2, CellKind.BASE));
    b.setCell(1, 1, new Cell(1, CellKind.NORMAL));
    b.setCell(1, 2, new Cell(1, CellKind.NORMAL));
    b.setCell(size - 2, size - 2, new Cell(2, CellKind.NORMAL));
    return b;
  }

  @AfterEach
  public void resetDefault() {
    GoBotSearcher.configureDefaultLeafEval(GoBotSearcher.LeafEval.HAND_TUNED, null);
  }

  @Test
  public void leafIsTheRawEvalRounded() {
    // No scale knob: the evaluator's output IS the leaf score (v3 was fitted in hand-tuned units).
    NNUEv3Evaluator v3 = V3TestEvaluators.constant(1234.6);
    assertEquals(1235L, GoBotSearcher.nnueV3Leaf(board(12), 1, 3, v3));
  }

  @Test
  public void clampsStrictlyBelowMate() {
    long hi = GoBotSearcher.nnueV3Leaf(board(12), 1, 3, V3TestEvaluators.constant(1e12));
    long lo = GoBotSearcher.nnueV3Leaf(board(12), 1, 3, V3TestEvaluators.constant(-1e12));
    assertEquals(GoBotSearcher.NNUE_CLAMP, hi);
    assertEquals(-GoBotSearcher.NNUE_CLAMP, lo);
    assertTrue(hi < GoBotSearcher.MATE_SCORE && lo > -GoBotSearcher.MATE_SCORE);
  }

  @Test
  public void orientedToRequestedPlayerAndZeroSum() {
    Board b = board(12); // player 1 has two NORMAL stones to player 2's one
    NNUEv3Evaluator v3 = V3TestEvaluators.stoneCount();
    long forP1 = GoBotSearcher.nnueV3Leaf(b, 1, 3, v3);
    long forP2 = GoBotSearcher.nnueV3Leaf(b, 2, 3, v3);
    assertEquals(1L, forP1, "higher = better for the queried player");
    assertEquals(forP1, -forP2, "zero-sum: the mirror perspective is the exact negative");
  }

  @Test
  public void configureSelectsTheV3Leaf() {
    NNUEv3Evaluator v3 = V3TestEvaluators.stoneCount();
    GoBotSearcher.configureDefaultLeafEvalV3(GoBotSearcher.LeafEval.NNUEV3, v3);
    GoBotSearcher s = GoBotSearcher.newSearcher(GoState.fromBoard(board(12), 1, 3, new boolean[4]));
    assertEquals(GoBotSearcher.LeafEval.NNUEV3, s.leafMode);
    assertSame(v3, s.nnueV3);

    GoResult r1 = GoBotSearcher.chooseDepth(GoState.fromBoard(board(12), 1, 3, new boolean[4]), 3);
    GoResult r2 = GoBotSearcher.chooseDepth(GoState.fromBoard(board(12), 1, 3, new boolean[4]), 3);
    assertNotNull(r1, "v3-leaf search completes at fixed depth");
    assertEquals(r1.action, r2.action, "deterministic action");
    assertEquals(r1.score, r2.score, "deterministic score");
  }

  @Test
  public void nonTwelveByTwelveFallsBackToHandTuned() {
    GoState state = GoState.fromBoard(board(8), 1, 3, new boolean[4]);
    GoResult handTuned = GoBotSearcher.chooseDepth(state, 3);

    // A 12x12-only accumulator would throw here; the leaf must fall back instead.
    GoBotSearcher.configureDefaultLeafEvalV3(
        GoBotSearcher.LeafEval.NNUEV3, V3TestEvaluators.constant(9999));
    GoResult withV3 = GoBotSearcher.chooseDepth(state, 3);

    assertNotNull(withV3, "non-12x12 board must not crash the v3 leaf");
    assertEquals(handTuned.action, withV3.action, "falls back to the hand-tuned leaf");
    assertEquals(handTuned.score, withV3.score);
  }

  @Test
  public void defaultOffIsUnchanged() {
    GoState state = GoState.fromBoard(board(12), 1, 3, new boolean[4]);
    GoBotSearcher s = GoBotSearcher.newSearcher(state);
    assertEquals(GoBotSearcher.LeafEval.HAND_TUNED, s.leafMode, "EVAL unset => hand-tuned leaf");
    GoResult before = GoBotSearcher.chooseDepth(state, 3);

    GoBotSearcher.LeafConfig prev =
        GoBotSearcher.configureDefaultLeafEvalV3(
            GoBotSearcher.LeafEval.NNUEV3, V3TestEvaluators.constant(1e6));
    GoBotSearcher.chooseDepth(state, 3);
    GoBotSearcher.restoreDefaultLeafEval(prev);

    GoResult after = GoBotSearcher.chooseDepth(state, 3);
    assertEquals(before.action, after.action, "default-OFF play is byte-identical");
    assertEquals(before.score, after.score);
  }
}
