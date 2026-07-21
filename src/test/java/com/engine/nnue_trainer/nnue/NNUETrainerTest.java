package com.engine.nnue_trainer.nnue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class NNUETrainerTest {

  @TempDir Path tempDir;

  @Test
  public void trainEpochReducesLossOnLearnableData() {
    NNUETrainer trainer = new NNUETrainer(7L);
    List<NNUETrainer.TrainingExample> examples = learnableExamples();

    float initialMse = trainer.meanSquaredError(examples);
    for (int epoch = 0; epoch < 6; epoch++) {
      trainer.trainEpoch(examples, epoch);
    }
    float finalMse = trainer.meanSquaredError(examples);

    assertTrue(finalMse < initialMse, "expected final MSE below initial MSE");
  }

  @Test
  public void savedWeightsLoadWithSameOutput() throws Exception {
    NNUETrainer trainer = new NNUETrainer(11L);
    List<NNUETrainer.TrainingExample> examples = learnableExamples();
    trainer.trainEpoch(examples, 0);

    NNUEModel beforeSave = trainer.createModel();
    Path weights = tempDir.resolve("nnue_weights.json");
    trainer.saveWeights(weights);
    NNUEModel afterLoad = NNUEModel.load(weights);

    float[] input = examples.get(0).features;
    assertEquals(beforeSave.forward(input), afterLoad.forward(input), 1e-5f);
  }

  private static List<NNUETrainer.TrainingExample> learnableExamples() {
    List<NNUETrainer.TrainingExample> examples = new ArrayList<>();
    for (int i = 0; i < 16; i++) {
      float[] features = new float[NNUETrainer.INPUT_SIZE];
      features[i] = 1.0f;
      examples.add(new NNUETrainer.TrainingExample(features, 1.0f));
    }
    for (int i = 16; i < 32; i++) {
      float[] features = new float[NNUETrainer.INPUT_SIZE];
      features[i] = 1.0f;
      examples.add(new NNUETrainer.TrainingExample(features, -1.0f));
    }
    return examples;
  }
}
