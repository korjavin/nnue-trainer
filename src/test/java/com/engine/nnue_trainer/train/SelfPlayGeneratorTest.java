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
}
