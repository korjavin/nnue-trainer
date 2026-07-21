package com.engine.nnue_trainer.nnue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class NNUETrainer {
  public static final int INPUT_SIZE = 864;
  public static final int HIDDEN_SIZE = 256;
  public static final int EPOCHS = 40;
  public static final int BATCH_SIZE = 256;
  public static final float INITIAL_LR = 0.01f;

  private final Random rng;
  private final float[][] hiddenWeights;
  private final float[] hiddenBiases;
  private final float[] outputWeights;
  private float outputBias;

  public NNUETrainer(long seed) {
    this.rng = new Random(seed);
    this.hiddenWeights = new float[HIDDEN_SIZE][INPUT_SIZE];
    this.hiddenBiases = new float[HIDDEN_SIZE];
    this.outputWeights = new float[HIDDEN_SIZE];

    float hiddenStdDev = (float) Math.sqrt(2.0 / INPUT_SIZE);
    float outputStdDev = (float) Math.sqrt(2.0 / HIDDEN_SIZE);
    for (int hidden = 0; hidden < HIDDEN_SIZE; hidden++) {
      for (int input = 0; input < INPUT_SIZE; input++) {
        hiddenWeights[hidden][input] = (float) (rng.nextGaussian() * hiddenStdDev);
      }
      outputWeights[hidden] = (float) (rng.nextGaussian() * outputStdDev);
    }
  }

  public TrainingResult train(List<TrainingExample> examples) {
    validateExamples(examples);
    List<Float> mseByEpoch = new ArrayList<>();
    for (int epoch = 0; epoch < EPOCHS; epoch++) {
      mseByEpoch.add(trainEpoch(examples, epoch));
    }
    return new TrainingResult(mseByEpoch);
  }

  public float trainEpoch(List<TrainingExample> examples, int epoch) {
    validateExamples(examples);
    float learningRate = INITIAL_LR / (1.0f + 0.1f * epoch);
    List<Integer> indices = shuffledIndices(examples.size());
    float totalLoss = 0.0f;

    for (int start = 0; start < examples.size(); start += BATCH_SIZE) {
      int end = Math.min(start + BATCH_SIZE, examples.size());
      int batchSize = end - start;

      float[][] preActivation = new float[batchSize][HIDDEN_SIZE];
      float[][] hidden = new float[batchSize][HIDDEN_SIZE];
      float[] error = new float[batchSize];

      for (int batch = 0; batch < batchSize; batch++) {
        TrainingExample example = examples.get(indices.get(start + batch));
        for (int node = 0; node < HIDDEN_SIZE; node++) {
          float sum = hiddenBiases[node];
          for (int input = 0; input < INPUT_SIZE; input++) {
            float feature = example.features[input];
            if (feature != 0.0f) {
              sum += hiddenWeights[node][input] * feature;
            }
          }
          preActivation[batch][node] = sum;
          hidden[batch][node] = clippedRelu(sum);
        }

        float output = outputBias;
        for (int node = 0; node < HIDDEN_SIZE; node++) {
          output += outputWeights[node] * hidden[batch][node];
        }
        error[batch] = output - example.target;
        totalLoss += error[batch] * error[batch];
      }

      float[] outputGradient = new float[batchSize];
      float[] outputWeightGradient = new float[HIDDEN_SIZE];
      float outputBiasGradient = 0.0f;
      for (int batch = 0; batch < batchSize; batch++) {
        outputGradient[batch] = error[batch] / batchSize;
        outputBiasGradient += outputGradient[batch];
        for (int node = 0; node < HIDDEN_SIZE; node++) {
          outputWeightGradient[node] += hidden[batch][node] * outputGradient[batch];
        }
      }

      float[][] hiddenWeightGradient = new float[HIDDEN_SIZE][INPUT_SIZE];
      float[] hiddenBiasGradient = new float[HIDDEN_SIZE];
      for (int batch = 0; batch < batchSize; batch++) {
        TrainingExample example = examples.get(indices.get(start + batch));
        for (int node = 0; node < HIDDEN_SIZE; node++) {
          if (preActivation[batch][node] < 0.0f || preActivation[batch][node] > 127.0f) {
            continue;
          }
          float hiddenGradient = outputGradient[batch] * outputWeights[node];
          hiddenBiasGradient[node] += hiddenGradient;
          for (int input = 0; input < INPUT_SIZE; input++) {
            float feature = example.features[input];
            if (feature != 0.0f) {
              hiddenWeightGradient[node][input] += hiddenGradient * feature;
            }
          }
        }
      }

      for (int node = 0; node < HIDDEN_SIZE; node++) {
        hiddenBiases[node] -= learningRate * hiddenBiasGradient[node];
        outputWeights[node] -= learningRate * outputWeightGradient[node];
        for (int input = 0; input < INPUT_SIZE; input++) {
          hiddenWeights[node][input] -= learningRate * hiddenWeightGradient[node][input];
        }
      }
      outputBias -= learningRate * outputBiasGradient;
    }

    return totalLoss / examples.size();
  }

  public float meanSquaredError(List<TrainingExample> examples) {
    validateExamples(examples);
    NNUEModel model = createModel();
    float total = 0.0f;
    for (TrainingExample example : examples) {
      float error = model.forward(example.features) - example.target;
      total += error * error;
    }
    return total / examples.size();
  }

  public NNUEModel createModel() {
    return NNUEModel.fromWeightsData(toWeightsData());
  }

  public NNUEModel.WeightsData toWeightsData() {
    NNUEModel.WeightsData data = new NNUEModel.WeightsData();
    data.hiddenWeights = copy(hiddenWeights);
    data.hiddenBiases = hiddenBiases.clone();
    data.outputWeights = outputWeights.clone();
    data.outputBias = outputBias;
    return data;
  }

  public void saveWeights(Path path) throws IOException {
    Path parent = path.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    new ObjectMapper().writeValue(path.toFile(), toWeightsData());
  }

  private static float clippedRelu(float value) {
    return Math.max(0.0f, Math.min(127.0f, value));
  }

  private static void validateExamples(List<TrainingExample> examples) {
    if (examples == null || examples.isEmpty()) {
      throw new IllegalArgumentException("Training data must not be empty");
    }
    for (TrainingExample example : examples) {
      if (example.features.length != INPUT_SIZE) {
        throw new IllegalArgumentException("Feature vector must have length " + INPUT_SIZE);
      }
    }
  }

  private List<Integer> shuffledIndices(int size) {
    List<Integer> indices = new ArrayList<>(size);
    for (int i = 0; i < size; i++) {
      indices.add(i);
    }
    Collections.shuffle(indices, rng);
    return indices;
  }

  private static float[][] copy(float[][] source) {
    float[][] result = new float[source.length][];
    for (int i = 0; i < source.length; i++) {
      result[i] = source[i].clone();
    }
    return result;
  }

  public static class TrainingExample {
    public final float[] features;
    public final float target;

    public TrainingExample(float[] features, float target) {
      this.features = features;
      this.target = target;
    }
  }

  public static class TrainingResult {
    public final List<Float> mseByEpoch;

    public TrainingResult(List<Float> mseByEpoch) {
      this.mseByEpoch = List.copyOf(mseByEpoch);
    }

    public float finalMse() {
      return mseByEpoch.get(mseByEpoch.size() - 1);
    }
  }
}
