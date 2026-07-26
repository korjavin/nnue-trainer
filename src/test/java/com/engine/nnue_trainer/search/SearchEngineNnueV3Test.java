package com.engine.nnue_trainer.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.v3.V3TestEvaluators;
import org.junit.jupiter.api.Test;

/**
 * Opt-in guarantee for {@code EVAL=NNUEV3}: the flag routes the leaf eval through the v3 evaluator,
 * default OFF leaves the pre-existing eval untouched, and neither a non-12x12 board nor a failed
 * weights load may propagate out of {@code evaluate}.
 */
public class SearchEngineNnueV3Test {

  private static final float MARKER = 4242.0f;

  private static Board board(int size) {
    Board b = new Board(size, size);
    b.setCell(0, 0, new Cell(1, CellKind.BASE));
    b.setCell(size - 1, size - 1, new Cell(2, CellKind.BASE));
    b.setCell(2, 2, new Cell(1, CellKind.NORMAL));
    b.setCell(3, 3, new Cell(1, CellKind.NORMAL));
    b.setCell(size - 3, size - 3, new Cell(2, CellKind.NORMAL));
    return b;
  }

  @Test
  public void flagOffLeavesBaselineUnchangedFlagOnRoutesToV3() {
    SearchEngine engine = new SearchEngine();
    engine.setNnueV3Evaluator(V3TestEvaluators.constant(MARKER));
    Board b = board(12);

    assertFalse(engine.isUseNnueV3Eval(), "v3 eval must default OFF");
    float baseline = engine.evaluate(b, null, 1, 1);
    assertNotEquals(MARKER, baseline, "flag OFF must not route through v3");

    engine.setUseNnueV3Eval(true);
    assertEquals(
        MARKER,
        engine.evaluate(b, null, 1, 1),
        0.0f,
        "flag ON must route the leaf eval through the v3 evaluator");

    engine.setUseNnueV3Eval(false);
    assertEquals(baseline, engine.evaluate(b, null, 1, 1), 0.0f, "turning it back off restores it");
  }

  @Test
  public void opponentToMoveLeafQueriesTheMoverAndNegates() {
    SearchEngine engine = new SearchEngine();
    engine.setNnueV3Evaluator(V3TestEvaluators.selfStones()); // eval(b, stm) == stm's NORMAL stones
    engine.setUseNnueV3Eval(true);
    Board b = board(12); // player 1 has 2 NORMALs, player 2 has 1

    // Mover == perspective: straight through, in the training convention (scored player == mover).
    assertEquals(
        2.0f, engine.evaluate(b, null, 1, 1), 0.0f, "own-move leaf scores the perspective");
    // Opponent to move: the model is queried from the mover (1 stone) and negated into player 1's
    // frame. Querying player 1 directly would give +2 and evaluate the leaf a tempo out of
    // distribution.
    assertEquals(
        -1.0f,
        engine.evaluate(b, null, 1, 2),
        0.0f,
        "opponent-to-move leaf negates the mover view");
  }

  @Test
  public void nonTwelveByTwelveFallsBackInsteadOfThrowing() {
    SearchEngine engine = new SearchEngine();
    engine.setNnueV3Evaluator(V3TestEvaluators.constant(MARKER));
    engine.setUseNnueV3Eval(true);
    Board b = board(8);

    // 2 own pieces - 1 opponent piece; the deterministic piece-count baseline, not the v3 marker.
    assertEquals(
        1.0f, engine.evaluate(b, null, 1, 1), 0.0f, "non-12x12 falls back to the baseline");
  }

  @Test
  public void failedWeightsLoadFallsBackInsteadOfPropagating() {
    String prev = System.getProperty("NNUEV3_WEIGHTS");
    System.setProperty("NNUEV3_WEIGHTS", "target/no-such-v3-weights.json");
    try {
      SearchEngine engine = new SearchEngine(); // no injected evaluator -> real (failing) load
      Board b = board(12);
      float baseline = engine.evaluate(b, null, 1, 1); // flag still OFF
      engine.setUseNnueV3Eval(true);
      // Exactly the baseline, not merely "finite": a laxer assert would also pass if a shared
      // evaluator loaded by some earlier test served this call instead of the fallback.
      assertEquals(
          baseline,
          engine.evaluate(b, null, 1, 1),
          0.0f,
          "a failed load must fall back to the baseline eval, not throw");
    } finally {
      if (prev == null) {
        System.clearProperty("NNUEV3_WEIGHTS");
      } else {
        System.setProperty("NNUEV3_WEIGHTS", prev);
      }
    }
  }

  @Test
  public void envFlagSelectsV3() {
    String prev = System.getProperty("EVAL");
    try {
      System.setProperty("EVAL", "NNUEV3");
      assertTrue(new SearchEngine().isUseNnueV3Eval(), "EVAL=NNUEV3 selects the v3 leaf");
      System.setProperty("EVAL", "HANDTUNED");
      assertFalse(new SearchEngine().isUseNnueV3Eval(), "another EVAL value must not select v3");
      System.clearProperty("EVAL");
      assertFalse(new SearchEngine().isUseNnueV3Eval(), "EVAL unset must leave v3 OFF");
    } finally {
      if (prev == null) {
        System.clearProperty("EVAL");
      } else {
        System.setProperty("EVAL", prev);
      }
    }
  }
}
