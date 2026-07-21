package com.engine.nnue_trainer.nnue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class NNUETrainer {
  public static final int INPUT_SIZE = 864;
  public static final int HIDDEN_SIZE = 256;
  public static final int EPOCHS = 40;
  public static final int BATCH_SIZE = 256;
  public static final float INITIAL_LR = 0.01f;

  private float[][] W1;
  private float[] b1;
  private float[] w2;
  private float b2;

  public NNUETrainer(long seed) {
    Random rng = new Random(seed);
    W1 = new float[HIDDEN_SIZE][INPUT_SIZE];
    b1 = new float[HIDDEN_SIZE];
    w2 = new float[HIDDEN_SIZE];
    b2 = 0.0f;

    float stdDev1 = (float) Math.sqrt(2.0 / INPUT_SIZE);
    float stdDev2 = (float) Math.sqrt(2.0 / HIDDEN_SIZE);

    for (int i = 0; i < HIDDEN_SIZE; i++) {
      for (int j = 0; j < INPUT_SIZE; j++) {
        W1[i][j] = (float) (rng.nextGaussian() * stdDev1);
      }
      w2[i] = (float) (rng.nextGaussian() * stdDev2);
    }
  }

  public NNUETrainer() {
    this(0L); // Default seed from Python script
  }

  public float clippedRelu(float x) {
    return Math.max(0.0f, Math.min(127.0f, x));
  }

  public void train(float[][] X, float[] y) {
    int n = X.length;
    if (n == 0 || n != y.length) {
      throw new IllegalArgumentException("Invalid dataset dimensions");
    }

    System.out.printf("Loaded %d positions | features=%d\n", n, X[0].length);
    Random rng = new Random(0L); // Seed for shuffling, matching python behavior

    for (int epoch = 0; epoch < EPOCHS; epoch++) {
      float lr = INITIAL_LR / (1.0f + 0.1f * epoch);
      float totalLoss = 0.0f;

      List<Integer> indices = IntStream.range(0, n).boxed().collect(Collectors.toList());
      Collections.shuffle(indices, rng);

      for (int start = 0; start < n; start += BATCH_SIZE) {
        int end = Math.min(start + BATCH_SIZE, n);
        int m = end - start;

        float[][] batchX = new float[m][INPUT_SIZE];
        float[] batchY = new float[m];

        for (int i = 0; i < m; i++) {
          int idx = indices.get(start + i);
          batchX[i] = X[idx];
          batchY[i] = y[idx];
        }

        // Forward pass
        float[][] pre = new float[m][HIDDEN_SIZE];
        float[][] h = new float[m][HIDDEN_SIZE];
        float[] out = new float[m];
        float[] err = new float[m];

        for (int i = 0; i < m; i++) {
          float[] xb = batchX[i];
          for (int j = 0; j < HIDDEN_SIZE; j++) {
            float sum = b1[j];
            for (int k = 0; k < INPUT_SIZE; k++) {
              sum += W1[j][k] * xb[k];
            }
            pre[i][j] = sum;
            h[i][j] = clippedRelu(sum);
          }

          float outSum = b2;
          for (int j = 0; j < HIDDEN_SIZE; j++) {
            outSum += w2[j] * h[i][j];
          }
          out[i] = outSum;
          err[i] = out[i] - batchY[i];
          totalLoss += err[i] * err[i];
        }

        // Backward pass
        float[] d_out = new float[m];
        for (int i = 0; i < m; i++) {
          d_out[i] = err[i] / m;
        }

        float[] dw2 = new float[HIDDEN_SIZE];
        float db2 = 0.0f;
        for (int i = 0; i < m; i++) {
          db2 += d_out[i];
          for (int j = 0; j < HIDDEN_SIZE; j++) {
            dw2[j] += h[i][j] * d_out[i];
          }
        }

        float[][] dh = new float[m][HIDDEN_SIZE];
        for (int i = 0; i < m; i++) {
          for (int j = 0; j < HIDDEN_SIZE; j++) {
            float grad = d_out[i] * w2[j];
            if (pre[i][j] >= 0.0f && pre[i][j] <= 127.0f) {
              dh[i][j] = grad;
            } else {
              dh[i][j] = 0.0f;
            }
          }
        }

        float[][] dw1 = new float[HIDDEN_SIZE][INPUT_SIZE];
        float[] db1 = new float[HIDDEN_SIZE];
        for (int i = 0; i < m; i++) {
          for (int j = 0; j < HIDDEN_SIZE; j++) {
            db1[j] += dh[i][j];
            for (int k = 0; k < INPUT_SIZE; k++) {
              dw1[j][k] += dh[i][j] * batchX[i][k];
            }
          }
        }

        // Update weights
        for (int j = 0; j < HIDDEN_SIZE; j++) {
          b1[j] -= lr * db1[j];
          for (int k = 0; k < INPUT_SIZE; k++) {
            W1[j][k] -= lr * dw1[j][k];
          }
          w2[j] -= lr * dw2[j];
        }
        b2 -= lr * db2;
      }

      System.out.printf(
          "Epoch %d/%d - MSE: %.5f (lr %.5f)\n", epoch + 1, EPOCHS, totalLoss / n, lr);
    }
  }

  public void saveWeights(String path) throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    WeightsData data = new WeightsData();
    data.hiddenWeights = W1;
    data.hiddenBiases = b1;
    data.outputWeights = w2;
    data.outputBias = b2;

    File file = new File(path);
    if (file.getParentFile() != null) {
      file.getParentFile().mkdirs();
    }
    mapper.writeValue(file, data);
    System.out.println("Saved trained NNUE weights to " + path);
  }

  public NNUEModel createModel() {
    return new NNUEModel(W1, b1, w2, b2);
  }

  private static class WeightsData {
    public float[][] hiddenWeights;
    public float[] hiddenBiases;
    public float[] outputWeights;
    public float outputBias;
  }
}
