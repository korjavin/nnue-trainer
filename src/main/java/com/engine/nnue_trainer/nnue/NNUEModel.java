package com.engine.nnue_trainer.nnue;

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

  public static NNUEModel createDefault() {
    int inputSize = 1152;
    int hiddenSize = 256;
    float[][] hiddenWeights = new float[hiddenSize][inputSize];
    float[] hiddenBiases = new float[hiddenSize];
    float[] outputWeights = new float[hiddenSize];
    float outputBias = 0.0f;

    return new NNUEModel(hiddenWeights, hiddenBiases, outputWeights, outputBias);
  }

  public float forward(float[] input) {
    if (input.length != 1152) {
      throw new IllegalArgumentException("Input array must have length 1152");
    }

    int hiddenSize = hiddenWeights.length;
    float[] hiddenLayer = new float[hiddenSize];

    for (int i = 0; i < hiddenSize; i++) {
      float sum = hiddenBiases[i];
      for (int j = 0; j < 1152; j++) {
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
