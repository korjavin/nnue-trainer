package com.engine.nnue_trainer.train;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.engine.nnue_trainer.mcts.MctsSearcher;
import com.engine.nnue_trainer.mcts.MctsSide;
import com.engine.nnue_trainer.nnue.NNUEModel;
import com.engine.nnue_trainer.search.gobot.GoBotSearcher;
import com.engine.nnue_trainer.v3.V3TestEvaluators;
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

  /**
   * {@code applyLeaf} must dispatch a v3 <b>net</b> evaluator to the v3 leaf. Before the {@code
   * V3Eval} dispatch it fell through to the v1 branch and died on {@code (NNUEModel) side}, so a
   * match that merely completes is the assertion.
   */
  @Test
  void v3NetSideDispatchesToTheV3Leaf() {
    GauntletMatch.Result r =
        GauntletMatch.play(V3TestEvaluators.selfStonesNet(), null, fastConfig());

    assertEquals(fastConfig().games, r.wins + r.losses + r.draws, "every game counted once: " + r);
  }

  /** An {@link MctsSide} plays with the PUCT searcher (fixed sims) against the GoBot clone. */
  @Test
  void mctsSideDispatchesAndCompletes() {
    MctsSide mcts = new MctsSide(new MctsSearcher.Config(), 64);

    GauntletMatch.Result r = GauntletMatch.play(mcts, null, fastConfig());

    assertEquals(fastConfig().games, r.wins + r.losses + r.draws, "every game counted once: " + r);
  }

  /** {@code moveMillis} drives both sides by wall clock — the plan's rung-1 production control. */
  @Test
  void moveMillisDeadlineControlCompletes() {
    GauntletMatch.Config cfg = new GauntletMatch.Config();
    cfg.games = 2;
    cfg.maxTurns = 3;
    cfg.fixedDepth = 0;
    cfg.nodeLimit = 0;
    cfg.moveMillis = 20; // tiny budget: exercises chooseWithDeadline + MCTS deadline path

    GauntletMatch.Result r =
        GauntletMatch.play(new MctsSide(new MctsSearcher.Config(), 0), null, cfg);

    assertEquals(cfg.games, r.wins + r.losses + r.draws, "every game counted once: " + r);
  }
}
