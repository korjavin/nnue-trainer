package com.engine.nnue_trainer.train;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.engine.nnue_trainer.search.gobot.GoBotSearcher;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Task 3 (Fix B): offline GoBot self-play with softmax {@code exploreTemperature} sampling and
 * on-export position dedup.
 */
class SelfPlayExploreDedupTest {

  private static SelfPlayGenerator.Config baseConfig() {
    SelfPlayGenerator.Config config = new SelfPlayGenerator.Config();
    config.searchMode = SelfPlayGenerator.SearchMode.GOBOT;
    config.labelMode = SelfPlayGenerator.LabelMode.TD_LEAF;
    config.tdLambda = 0.5;
    config.gobotFixedDepth = 1; // cheap + bypasses the opening book
    config.numGames = 3;
    config.maxTurns = 6;
    config.seed = 7;
    config.epsilon = 0.0; // isolate the temperature path
    return config;
  }

  @AfterEach
  void resetDefault() {
    GoBotSearcher.configureDefaultLeafEval(GoBotSearcher.LeafEval.HAND_TUNED, null);
  }

  @Test
  void samplingPathProducesFiniteInRangeTargets() {
    SelfPlayGenerator.Config config = baseConfig();
    config.exploreTemperature = 0.6;
    config.exploreTurns = 100; // sample across all turns

    SelfPlayGenerator.GenerationResult result = SelfPlayGenerator.generate(config, null);

    assertFalse(result.dataset.isEmpty(), "sampling self-play must produce records");
    for (SelfPlayGenerator.TrainingRecord rec : result.dataset) {
      assertEquals(864, rec.features.length, "feature vector length");
      assertTrue(Float.isFinite(rec.target), "target must be finite");
      assertTrue(rec.target >= -1f && rec.target <= 1f, "target in [-1,1], got " + rec.target);
    }
  }

  @Test
  void dedupRemovesExactDuplicatePositions() {
    // Fully deterministic play (no exploration) replays the SAME game numGames times → duplicates.
    SelfPlayGenerator.Config on = baseConfig();
    on.dedup = true;
    SelfPlayGenerator.GenerationResult deduped = SelfPlayGenerator.generate(on, null);

    SelfPlayGenerator.Config off = baseConfig();
    off.dedup = false;
    SelfPlayGenerator.GenerationResult raw = SelfPlayGenerator.generate(off, null);

    // Deterministic identical games ⇒ dedup drops duplicates.
    assertTrue(
        deduped.dataset.size() < raw.dataset.size(),
        "dedup should drop duplicate positions from identical games");
    // Every emitted position hash is unique, and equals the reported unique yield.
    Set<Integer> hashes = new HashSet<>();
    for (SelfPlayGenerator.TrainingRecord rec : deduped.dataset) {
      assertTrue(hashes.add(Arrays.hashCode(rec.features)), "no duplicate positions after dedup");
    }
    assertEquals(hashes.size(), deduped.dataset.size(), "dataset.size() == uniquePositions");
    assertTrue(
        deduped.totalPositionsSeen >= deduped.dataset.size(),
        "totalPositionsSeen counts pre-dedup positions");
  }

  @Test
  void seededSamplingRunIsReproducible() {
    SelfPlayGenerator.Config a = baseConfig();
    a.exploreTemperature = 0.6;
    a.exploreTurns = 100;
    SelfPlayGenerator.GenerationResult r1 = SelfPlayGenerator.generate(a, null);

    SelfPlayGenerator.Config b = baseConfig();
    b.exploreTemperature = 0.6;
    b.exploreTurns = 100;
    SelfPlayGenerator.GenerationResult r2 = SelfPlayGenerator.generate(b, null);

    assertEquals(r1.dataset.size(), r2.dataset.size(), "same seed ⇒ same record count");
    for (int i = 0; i < r1.dataset.size(); i++) {
      assertEquals(
          r1.dataset.get(i).target, r2.dataset.get(i).target, 0f, "same seed ⇒ identical targets");
      assertArrayEquals(
          r1.dataset.get(i).features,
          r2.dataset.get(i).features,
          0f,
          "same seed ⇒ identical positions");
    }
  }
}
