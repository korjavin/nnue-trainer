package com.engine.nnue_trainer.train;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.engine.nnue_trainer.search.gobot.GoBotSearcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Task 4 (Fix C): re-measure diversity. Exploration OFF replays the same deterministic GoBot game
 * every time (the reported low-uniqueness baseline); ON with a seeded softmax temperature yields a
 * materially higher unique-position ratio. Kept fast: few shallow games.
 */
class SelfPlayDiversityTest {

  private static SelfPlayGenerator.Config baseConfig() {
    SelfPlayGenerator.Config config = new SelfPlayGenerator.Config();
    config.searchMode = SelfPlayGenerator.SearchMode.GOBOT;
    config.labelMode = SelfPlayGenerator.LabelMode.TD_LEAF;
    config.tdLambda = 0.5;
    config.gobotFixedDepth = 1; // cheap + bypasses the opening book
    config.numGames = 4;
    config.maxTurns = 8;
    config.seed = 11;
    config.epsilon = 0.0; // isolate the temperature path (no uniform-random flailing)
    config.dedup = false; // keep every position so distinctGameRatio reflects raw play
    return config;
  }

  @AfterEach
  void resetDefault() {
    GoBotSearcher.configureDefaultLeafEval(GoBotSearcher.LeafEval.HAND_TUNED, null);
  }

  @Test
  void explorationOnRaisesUniquePositionRatio() {
    SelfPlayGenerator.Config off = baseConfig();
    off.exploreTemperature = 0.0; // OFF ⇒ deterministic best-move
    SelfPlayGenerator.GenerationResult baseline = SelfPlayGenerator.generate(off, null);

    SelfPlayGenerator.Config on = baseConfig();
    on.exploreTemperature = 0.6;
    on.exploreTurns = 100; // sample across all turns
    SelfPlayGenerator.GenerationResult diverse = SelfPlayGenerator.generate(on, null);

    // OFF is near-duplicate: all numGames replay the same game, so unique ≈ 1/numGames of total.
    assertTrue(
        baseline.distinctGameRatio <= 0.6,
        "OFF baseline should be near-duplicate, got " + baseline.distinctGameRatio);
    // ON diversifies: materially higher unique-position ratio.
    assertTrue(
        diverse.distinctGameRatio > baseline.distinctGameRatio + 0.15,
        "ON ratio ("
            + diverse.distinctGameRatio
            + ") should be materially higher than OFF ("
            + baseline.distinctGameRatio
            + ")");
  }

  @Test
  void explorationOffIsDeterministic() {
    SelfPlayGenerator.GenerationResult a = SelfPlayGenerator.generate(baseConfig(), null);
    SelfPlayGenerator.GenerationResult b = SelfPlayGenerator.generate(baseConfig(), null);
    assertEquals(a.dataset.size(), b.dataset.size(), "OFF is reproducible: same record count");
    assertEquals(
        a.distinctGameRatio,
        b.distinctGameRatio,
        0.0,
        "OFF is reproducible: identical unique-position ratio");
  }
}
