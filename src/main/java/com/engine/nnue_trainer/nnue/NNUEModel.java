package com.engine.nnue_trainer.nnue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;

public class NNUEModel {
  private final float[][] hiddenWeights;
  private final float[] hiddenBiases;
  private final float[] outputWeights;
  private final float outputBias;

  public NNUEModel(
      float[][] hiddenWeights, float[] hiddenBiases, float[] outputWeights, float outputBias) {
    this.hiddenWeights = hiddenWeights;
    this.hiddenBiases = hiddenBiases;
    this.outputWeights = outputWeights;
    this.outputBias = outputBias;
  }

  private static NNUEModel cachedDefaultInstance = null;

  public static synchronized NNUEModel createDefault() {
    if (cachedDefaultInstance != null) {
      return cachedDefaultInstance;
    }
    try {
      ObjectMapper mapper = new ObjectMapper();
      InputStream is = NNUEModel.class.getResourceAsStream("/nnue_weights.json");
      if (is != null) {
        WeightsData data = mapper.readValue(is, WeightsData.class);
        System.out.println("Loaded trained NNUE weights from nnue_weights.json");
        cachedDefaultInstance =
            new NNUEModel(
                data.hiddenWeights, data.hiddenBiases, data.outputWeights, data.outputBias);
        return cachedDefaultInstance;
      }
    } catch (Exception e) {
      System.err.println("Failed to load nnue_weights.json: " + e.getMessage());
    }

    int inputSize = 864;
    int hiddenSize = 256;
    float[][] hiddenWeights = new float[hiddenSize][inputSize];
    float[] hiddenBiases = new float[hiddenSize];
    float[] outputWeights = new float[hiddenSize];
    float outputBias = 0.0f;

    cachedDefaultInstance = new NNUEModel(hiddenWeights, hiddenBiases, outputWeights, outputBias);
    return cachedDefaultInstance;
  }

  private static class WeightsData {
    public float[][] hiddenWeights;
    public float[] hiddenBiases;
    public float[] outputWeights;
    public float outputBias;
  }

  public float forward(float[] input) {
    if (input.length != 864) {
      throw new IllegalArgumentException("Input array must have length 864");
    }

    int hiddenSize = hiddenWeights.length;
    float[] hiddenLayer = new float[hiddenSize];

    for (int i = 0; i < hiddenSize; i++) {
      float sum = hiddenBiases[i];
      for (int j = 0; j < 864; j++) {
        sum += hiddenWeights[i][j] * input[j];
      }
      // Clipped ReLU
      hiddenLayer[i] = Math.max(0.0f, Math.min(127.0f, sum));
    }

    float output = outputBias;
    for (int i = 0; i < hiddenSize; i++) {
      output += outputWeights[i] * hiddenLayer[i];
    }

    return output;
  }

  public float[][] getHiddenWeights() {
    return hiddenWeights;
  }

  public float[] getHiddenBiases() {
    return hiddenBiases;
  }

  public float[] getOutputWeights() {
    return outputWeights;
  }

  public float getOutputBias() {
    return outputBias;
  }
}
