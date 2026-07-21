package com.engine.nnue_trainer.train;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import org.junit.jupiter.api.Test;

class SelfPlayGeneratorTest {

  @Test
  void testSingleGameGeneration() throws Exception {
    File tempDir = new File(System.getProperty("java.io.tmpdir"));
    File tempFile =
        new File(tempDir, "self_play_data_test_" + System.currentTimeMillis() + ".json");
    if (tempFile.exists()) {
      tempFile.delete();
    }

    SelfPlayGenerator.main(new String[] {"1", tempFile.getAbsolutePath()});

    assertTrue(tempFile.exists(), "Dataset JSON file should have been generated.");
    assertTrue(tempFile.length() > 0, "Dataset JSON file should not be empty.");

    // Clean up
    tempFile.delete();
  }

  @Test
  void testDiverseDatasetGeneration() {
    SelfPlayGenerator.Config config = new SelfPlayGenerator.Config();
    config.numGames = 1;
    config.maxTurns = 15;
    config.epsilon = 1.0;
    config.exploreTurns = 15;

    SelfPlayGenerator.GenerationResult result = SelfPlayGenerator.generate(config, null);

    assertTrue(result.dataset.size() > 0, "Dataset should not be empty.");
    assertTrue(
        result.distinctGameRatio > 0.8,
        "Distinct game ratio should be greater than 0.8, was: " + result.distinctGameRatio);
  }
}
