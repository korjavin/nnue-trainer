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
 * Opt-in guarantee for {@code EVAL=NNUEV3NET}, mirroring {@link SearchEngineNnueV3Test}: the flag
 * routes the leaf through the net, default OFF leaves the pre-existing eval untouched, and neither
 * a non-12x12 board nor a failed weights load may propagate out of {@code evaluate}.
 *
 * <p>Default-OFF is the load-bearing one: master auto-deploys to prod, so a default-path change
 * reaches the live bot.
 */
public class SearchEngineNnueV3NetTest {

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
  public void flagOffLeavesBaselineUnchangedFlagOnRoutesToTheNet() {
    SearchEngine engine = new SearchEngine();
    engine.setNnueV3NetEvaluator(V3TestEvaluators.constantNet(MARKER));
    Board b = board(12);

    assertFalse(engine.isUseNnueV3NetEval(), "v3 net eval must default OFF");
    float baseline = engine.evaluate(b, null, 1, 1);
    assertNotEquals(MARKER, baseline, "flag OFF must not route through the net");

    engine.setUseNnueV3NetEval(true);
    assertEquals(
        MARKER,
        engine.evaluate(b, null, 1, 1),
        0.0f,
        "flag ON must route the leaf eval through the v3 net");

    engine.setUseNnueV3NetEval(false);
    assertEquals(baseline, engine.evaluate(b, null, 1, 1), 0.0f, "turning it back off restores it");
  }

  /**
   * With every EVAL flag unset — the production default — an engine that has BOTH v3 evaluators
   * injected must still return the untouched baseline. This is the regression that would ship to
   * the live bot.
   */
  @Test
  public void defaultPathIgnoresBothV3Evaluators() {
    String prev = System.getProperty("EVAL");
    System.clearProperty("EVAL");
    try {
      SearchEngine wired = new SearchEngine();
      wired.setNnueV3NetEvaluator(V3TestEvaluators.constantNet(MARKER));
      wired.setNnueV3Evaluator(V3TestEvaluators.constant(MARKER + 1));
      SearchEngine bare = new SearchEngine();

      assertFalse(wired.isUseNnueV3NetEval(), "EVAL unset must leave the net leaf OFF");
      assertFalse(wired.isUseNnueV3Eval(), "EVAL unset must leave the linear v3 leaf OFF");
      Board b = board(12);
      assertEquals(
          bare.evaluate(b, null, 1, 1),
          wired.evaluate(b, null, 1, 1),
          0.0f,
          "default play must be byte-identical whether or not a v3 evaluator is present");
    } finally {
      if (prev != null) {
        System.setProperty("EVAL", prev);
      }
    }
  }

  @Test
  public void opponentToMoveLeafQueriesTheMoverAndNegates() {
    SearchEngine engine = new SearchEngine();
    engine.setNnueV3NetEvaluator(V3TestEvaluators.selfStonesNet());
    engine.setUseNnueV3NetEval(true);
    Board b = board(12); // player 1 has 2 NORMALs, player 2 has 1

    assertEquals(
        2.0f, engine.evaluate(b, null, 1, 1), 0.0f, "own-move leaf scores the perspective");
    assertEquals(
        -1.0f,
        engine.evaluate(b, null, 1, 2),
        0.0f,
        "opponent-to-move leaf negates the mover view");
  }

  @Test
  public void nonTwelveByTwelveFallsBackInsteadOfThrowing() {
    SearchEngine engine = new SearchEngine();
    engine.setNnueV3NetEvaluator(V3TestEvaluators.constantNet(MARKER));
    engine.setUseNnueV3NetEval(true);
    assertEquals(
        1.0f,
        engine.evaluate(board(8), null, 1, 1),
        0.0f,
        "non-12x12 falls back to the piece-count baseline");
  }

  @Test
  public void failedWeightsLoadFallsBackInsteadOfPropagating() {
    String prev = System.getProperty("NNUEV3NET_WEIGHTS");
    System.setProperty("NNUEV3NET_WEIGHTS", "target/no-such-v3-net.json");
    SearchEngine.resetSharedV3NetEvaluator();
    try {
      SearchEngine engine = new SearchEngine(); // no injected evaluator -> real (failing) load
      Board b = board(12);
      float baseline = engine.evaluate(b, null, 1, 1); // flag still OFF
      engine.setUseNnueV3NetEval(true);
      assertEquals(
          baseline,
          engine.evaluate(b, null, 1, 1),
          0.0f,
          "a failed load must fall back to the baseline eval, not throw");
    } finally {
      if (prev == null) {
        System.clearProperty("NNUEV3NET_WEIGHTS");
      } else {
        System.setProperty("NNUEV3NET_WEIGHTS", prev);
      }
      SearchEngine.resetSharedV3NetEvaluator();
    }
  }

  @Test
  public void envFlagSelectsTheNetAndOnlyTheNet() {
    String prev = System.getProperty("EVAL");
    try {
      System.setProperty("EVAL", "NNUEV3NET");
      SearchEngine e = new SearchEngine();
      assertTrue(e.isUseNnueV3NetEval(), "EVAL=NNUEV3NET selects the net leaf");
      assertFalse(e.isUseNnueV3Eval(), "EVAL=NNUEV3NET must not also select the linear leaf");
      System.setProperty("EVAL", "NNUEV3");
      assertFalse(
          new SearchEngine().isUseNnueV3NetEval(), "EVAL=NNUEV3 must not select the net leaf");
      System.clearProperty("EVAL");
      assertFalse(new SearchEngine().isUseNnueV3NetEval(), "EVAL unset must leave the net OFF");
    } finally {
      if (prev == null) {
        System.clearProperty("EVAL");
      } else {
        System.setProperty("EVAL", prev);
      }
    }
  }
}
