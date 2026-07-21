package com.engine.nnue_trainer.nnue;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

public class NNUETrainerTest {

  @Test
  public void testLossDecreases() {
    NNUETrainer trainer = new NNUETrainer(42L);

    // Generate some dummy data
    List<NNUETrainer.TrainingExample> data = new ArrayList<>();
    Random rng = new Random(42);
    for (int i = 0; i < 1000; i++) {
      float[] features = new float[864];
      // Random features
      for (int j = 0; j < 10; j++) {
        features[rng.nextInt(864)] = 1.0f;
      }
      float target = (rng.nextBoolean()) ? 1.0f : -1.0f;
      data.add(new NNUETrainer.TrainingExample(features, target));
    }

    float initialLoss = trainer.trainEpoch(data, 0);
    float finalLoss = initialLoss;

    for (int i = 1; i < 5; i++) {
      finalLoss = trainer.trainEpoch(data, i);
    }

    assertTrue(
        finalLoss < initialLoss,
        "Loss should decrease after 5 epochs. Initial: " + initialLoss + ", Final: " + finalLoss);
  }

  @Test
  public void testExportWeights() throws Exception {
    NNUETrainer trainer = new NNUETrainer(1L);
    File tempFile = File.createTempFile("nnue_weights", ".json");
    tempFile.deleteOnExit();

    trainer.exportWeights(tempFile.getAbsolutePath());

    assertTrue(tempFile.exists());
    assertTrue(tempFile.length() > 0);
  }
}
