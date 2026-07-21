package com.engine.nnue_trainer.nnue;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.Random;
import org.junit.jupiter.api.Test;

public class NNUETrainerTest {

  @Test
  public void testLossDecreases() {
    NNUETrainer trainer = new NNUETrainer(42L);

    // Create a tiny synthetic dataset
    int numSamples = 10;
    float[][] X = new float[numSamples][NNUETrainer.INPUT_SIZE];
    float[] y = new float[numSamples];
    Random rng = new Random(42L);

    for (int i = 0; i < numSamples; i++) {
      for (int j = 0; j < NNUETrainer.INPUT_SIZE; j++) {
        X[i][j] = rng.nextFloat();
      }
      y[i] = rng.nextBoolean() ? 1.0f : -1.0f;
    }

    // Evaluate initial MSE
    NNUEModel initialModel = trainer.createModel();
    float initialMSE = calculateMSE(initialModel, X, y);

    // Train
    trainer.train(X, y);

    // Evaluate final MSE
    NNUEModel finalModel = trainer.createModel();
    float finalMSE = calculateMSE(finalModel, X, y);

    assertTrue(
        finalMSE < initialMSE,
        "Loss did not decrease after training. Initial: " + initialMSE + ", Final: " + finalMSE);
  }

  @Test
  public void testExportedWeightsLoadCleanly() throws Exception {
    NNUETrainer trainer = new NNUETrainer(1L);
    String tempPath = "target/temp_weights.json";
    trainer.saveWeights(tempPath);

    File file = new File(tempPath);
    assertTrue(file.exists());

    // Test if we can read it back via NNUEModel logic (manually mimicking createDefault)
    com.fasterxml.jackson.databind.ObjectMapper mapper =
        new com.fasterxml.jackson.databind.ObjectMapper();
    NNUEModel.WeightsData data = mapper.readValue(file, NNUEModel.WeightsData.class);

    assertNotNull(data.hiddenWeights);
    assertNotNull(data.hiddenBiases);
    assertNotNull(data.outputWeights);

    file.delete();
  }

  private float calculateMSE(NNUEModel model, float[][] X, float[] y) {
    float mse = 0;
    for (int i = 0; i < X.length; i++) {
      float out = model.forward(X[i]);
      float err = out - y[i];
      mse += err * err;
    }
    return mse / X.length;
  }
}
