package com.engine.nnue_trainer.search.gobot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.engine.nnue_trainer.board.Action;
import com.engine.nnue_trainer.board.MoveAction;
import com.engine.nnue_trainer.board.Pos;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Task 1: the shared seeded exploration sampler. */
public class GoBotExplorationTest {

  private static GoResult result(int bestScore, int... altScores) {
    GoResult r = new GoResult(new MoveAction(new Pos(0, 0)));
    r.score = bestScore;
    r.depth = 3;
    r.searchComplete = true;
    List<RootMove> alts = new ArrayList<>();
    int i = 1;
    for (int s : altScores) {
      alts.add(new RootMove(new MoveAction(new Pos(i, i)), s));
      i++;
    }
    r.alternatives = alts;
    return r;
  }

  @Test
  public void disabledReturnsArgmax() {
    GoResult r = result(100, 90, 80);
    Action best = r.action;
    GoBotExploration ex = new GoBotExploration(false, 1.0, new Random(1));
    for (int i = 0; i < 50; i++) {
      assertSame(best, ex.sampleMove(r));
    }
  }

  @Test
  public void temperatureZeroIsArgmax() {
    GoResult r = result(100, 99, 98);
    GoBotExploration ex = new GoBotExploration(true, 0.0, new Random(1));
    assertSame(r.action, ex.sampleMove(r));
  }

  @Test
  public void seededReproducible() {
    GoResult r = result(100, 95, 90, 85);
    List<Action> a = new ArrayList<>();
    List<Action> b = new ArrayList<>();
    GoBotExploration exA = new GoBotExploration(true, 0.5, new Random(42));
    GoBotExploration exB = new GoBotExploration(true, 0.5, new Random(42));
    for (int i = 0; i < 20; i++) {
      a.add(exA.sampleMove(r));
      b.add(exB.sampleMove(r));
    }
    assertEquals(a, b, "same seed ⇒ same picks");
  }

  @Test
  public void highTemperatureCanPickNonBest() {
    // Nearly-flat scores + hot temperature ⇒ alternatives get real weight.
    GoResult r = result(100, 99, 98, 97);
    GoBotExploration ex = new GoBotExploration(true, 5.0, new Random(7));
    boolean pickedNonBest = false;
    for (int i = 0; i < 200; i++) {
      if (ex.sampleMove(r) != r.action) {
        pickedNonBest = true;
        break;
      }
    }
    assertTrue(pickedNonBest, "hot temperature must eventually pick an alternative");
  }

  @Test
  public void handTunedScaleScoresStillExplore() {
    // Real root scores from a 12x12 midgame with the challenger's DEFAULT hand-tuned leaf: an order
    // of magnitude above the NNUE band, so a fixed NNUE_SCALE softmax returned argmax ~96% of the
    // time and CHALLENGER_EXPLORE did nothing past the opening.
    GoResult r = result(14214, 11978, 11978, 10057, 10057);
    GoBotExploration ex = new GoBotExploration(true, 0.6, new Random(3));
    int nonBest = 0;
    for (int i = 0; i < 200; i++) {
      if (ex.sampleMove(r) != r.action) {
        nonBest++;
      }
    }
    assertTrue(
        nonBest >= 50, "hand-tuned-scale scores must still explore, got " + nonBest + "/200");
  }

  @Test
  public void emptyOrNullAlternativesSafe() {
    GoBotExploration ex = new GoBotExploration(true, 1.0, new Random(1));

    GoResult empty = result(100); // no alternatives
    assertSame(empty.action, ex.sampleMove(empty));

    GoResult nullAlts = new GoResult(new MoveAction(new Pos(2, 2)));
    nullAlts.alternatives = null;
    assertSame(nullAlts.action, ex.sampleMove(nullAlts));

    assertNull(ex.sampleMove(null));
  }

  @Test
  public void openingRandomizesOnlyWhenEnabled() {
    List<Action> legal =
        Arrays.asList(
            new MoveAction(new Pos(0, 1)),
            new MoveAction(new Pos(1, 0)),
            new MoveAction(new Pos(1, 1)));

    GoBotExploration off = new GoBotExploration(false, 1.0, new Random(1));
    assertNull(off.sampleOpening(legal), "disabled ⇒ null (keep canonical book move)");

    GoBotExploration on = new GoBotExploration(true, 1.0, new Random(1));
    Action pick = on.sampleOpening(legal);
    assertNotNull(pick);
    assertTrue(legal.contains(pick), "picked action must be legal");

    assertNull(on.sampleOpening(new ArrayList<>()), "empty legal ⇒ null");
  }

  @Test
  public void fromEnvDefaultsDisabled() {
    // No system properties / env set for these keys ⇒ disabled, defaultTemp.
    GoBotExploration ex =
        GoBotExploration.fromEnv("UNSET_EXPLORE_XYZ", "UNSET_TEMP_XYZ", "UNSET_SEED_XYZ", 0.6);
    assertFalse(ex.enabled);
    assertEquals(0.6, ex.temperature, 1e-9);
  }

  @Test
  public void fromEnvParsesSystemProperties() {
    String enk = "TEST_EXPLORE_GATE";
    String tk = "TEST_EXPLORE_TEMP";
    String sk = "TEST_EXPLORE_SEED";
    try {
      System.setProperty(enk, "true");
      System.setProperty(tk, "0.8");
      System.setProperty(sk, "123");
      GoBotExploration ex = GoBotExploration.fromEnv(enk, tk, sk, 0.6);
      assertTrue(ex.enabled);
      assertEquals(0.8, ex.temperature, 1e-9);

      // Seeded ⇒ same first pick as a fresh Random(123) on identical config.
      GoResult r = result(100, 95, 90, 85);
      GoBotExploration ref = new GoBotExploration(true, 0.8, new Random(123));
      assertSame(ref.sampleMove(r), ex.sampleMove(r));
    } finally {
      System.clearProperty(enk);
      System.clearProperty(tk);
      System.clearProperty(sk);
    }
  }
}
