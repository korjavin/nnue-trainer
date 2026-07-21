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

    tempFile.delete();
  }

  @Test
  void testEpsilonExplorationDiversity() {
    File tempDir = new File(System.getProperty("java.io.tmpdir"));
    File tempFile =
        new File(tempDir, "self_play_data_diversity_" + System.currentTimeMillis() + ".json");

    SelfPlayGenerator.Config config = new SelfPlayGenerator.Config(
        5, 0.5, 6, 2, 0, tempFile.getAbsolutePath());
    SelfPlayGenerator.GenerationResult result = SelfPlayGenerator.generateGames(config);

    assertTrue(result.distinctGameRatio() > 0.8, "Games should be diverse with distinct game ratio > 0.8");
    assertTrue(result.dataset().size() > 0, "Dataset should not be empty");

    tempFile.delete();
  }
}
