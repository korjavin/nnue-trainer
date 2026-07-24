package com.engine.nnue_trainer.train;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.engine.nnue_trainer.nnue.NNUEModel;
import com.engine.nnue_trainer.search.gobot.GoBotSearcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Task 1: the offline net-vs-net gate. Identical weights on both sides must land ~even (exactly
 * even over alternating colors, since both sides play the same deterministic game), and the whole
 * match is reproducible under a fixed node/depth budget. A shallow fixed depth keeps it fast.
 */
class GauntletMatchTest {

  @AfterEach
  void resetDefault() {
    GoBotSearcher.configureDefaultLeafEval(GoBotSearcher.LeafEval.HAND_TUNED, null);
  }

  private static GauntletMatch.Config fastConfig() {
    GauntletMatch.Config config = new GauntletMatch.Config();
    config.fixedDepth = 2; // cheap + bypasses the opening book
    config.games = 4;
    config.maxTurns = 8;
    return config;
  }

  @Test
  void identicalWeightsAreEvenAndDeterministic() {
    NNUEModel model = NNUEModel.createDefault();

    GauntletMatch.Result r = GauntletMatch.play(model, model, fastConfig());

    // Alternating colors with identical eval on both sides: each game plays out identically
    // regardless of which label is "A", so wins and losses must exactly cancel.
    assertEquals(r.wins, r.losses, "identical weights must be even: " + r);
    assertEquals(
        fastConfig().games, r.wins + r.losses + r.draws, "every game counted exactly once");
    assertEquals(0, r.margin(), "no promotion signal when nets are identical");

    // Same budget → byte-identical result (reproducible gate).
    GauntletMatch.Result again = GauntletMatch.play(model, model, fastConfig());
    assertEquals(r.wins, again.wins, "reproducible wins");
    assertEquals(r.losses, again.losses, "reproducible losses");
    assertEquals(r.draws, again.draws, "reproducible draws");
  }

  @Test
  void netVsHandTunedBarRuns() {
    NNUEModel model = NNUEModel.createDefault();

    // A null side is the hand-tuned bar — this is the challenger-vs-bar guard Task 2 needs.
    GauntletMatch.Result r = GauntletMatch.play(model, null, fastConfig());

    assertEquals(fastConfig().games, r.wins + r.losses + r.draws, "every game counted once: " + r);
  }
}
