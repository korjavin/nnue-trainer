package com.engine.nnue_trainer.train;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.engine.nnue_trainer.search.gobot.GoBotSearcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Task 3: self-play driven by the strong GoBot search (NNUE leaf) produces well-formed TD-leaf
 * records with targets in range. A shallow fixed depth keeps it fast and deterministic.
 */
class GoBotSelfPlayTest {

  private static SelfPlayGenerator.Config gobotConfig() {
    SelfPlayGenerator.Config config = new SelfPlayGenerator.Config();
    config.searchMode = SelfPlayGenerator.SearchMode.GOBOT;
    config.labelMode = SelfPlayGenerator.LabelMode.TD_LEAF;
    config.tdLambda = 0.5;
    config.gobotFixedDepth = 2; // cheap; also bypasses the opening book
    config.numGames = 2;
    config.maxTurns = 8;
    config.seed = 42;
    return config;
  }

  @AfterEach
  void resetDefault() {
    GoBotSearcher.configureDefaultLeafEval(GoBotSearcher.LeafEval.HAND_TUNED, null);
  }

  @Test
  void producesWellFormedRecordsWithTargetsInRange() {
    SelfPlayGenerator.GenerationResult result = SelfPlayGenerator.generate(gobotConfig(), null);

    assertFalse(result.dataset.isEmpty(), "GoBot self-play must produce records");
    for (SelfPlayGenerator.TrainingRecord rec : result.dataset) {
      assertEquals(864, rec.features.length, "feature vector length");
      assertTrue(Float.isFinite(rec.target), "target must be finite");
      assertTrue(rec.target >= -1f && rec.target <= 1f, "target in [-1,1], got " + rec.target);
    }
    assertTrue(
        result.distinctGameRatio >= 0.0 && result.distinctGameRatio <= 1.0,
        "distinct ratio is a fraction");
  }
}
