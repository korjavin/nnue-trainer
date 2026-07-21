package com.engine.nnue_trainer.nnue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class NNUETrainer {

  private static final int INPUT_SIZE = 864;
  private static final int HIDDEN_SIZE = 256;
  private static final int EPOCHS = 40;
  private static final int BATCH_SIZE = 256;
  private static final float INITIAL_LR = 0.01f;

  private float[][] W1; // [HIDDEN_SIZE][INPUT_SIZE]
  private float[] b1; // [HIDDEN_SIZE]
  private float[] w2; // [HIDDEN_SIZE]
  private float b2; // scalar

  private Random rng;

  public NNUETrainer(long seed) {
    this.rng = new Random(seed);
    this.W1 = new float[HIDDEN_SIZE][INPUT_SIZE];
    this.b1 = new float[HIDDEN_SIZE];
    this.w2 = new float[HIDDEN_SIZE];
    this.b2 = 0.0f;

    float stdW1 = (float) Math.sqrt(2.0 / INPUT_SIZE);
    for (int i = 0; i < HIDDEN_SIZE; i++) {
      for (int j = 0; j < INPUT_SIZE; j++) {
        W1[i][j] = (float) (rng.nextGaussian() * stdW1);
      }
    }

    float stdw2 = (float) Math.sqrt(2.0 / HIDDEN_SIZE);
    for (int i = 0; i < HIDDEN_SIZE; i++) {
      w2[i] = (float) (rng.nextGaussian() * stdw2);
    }
  }

  public static class TrainingExample {
    public float[] features; // size 864
    public float target;

    public TrainingExample(float[] features, float target) {
      this.features = features;
      this.target = target;
    }
  }

  private float clippedRelu(float x) {
    return Math.max(0.0f, Math.min(127.0f, x));
  }

  public float trainEpoch(List<TrainingExample> data, int epoch) {
    float lr = INITIAL_LR / (1.0f + 0.1f * epoch);
    int n = data.size();

    // Shuffle
    List<Integer> indices = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      indices.add(i);
    }
    Collections.shuffle(indices, rng);

    float totalLoss = 0.0f;

    for (int start = 0; start < n; start += BATCH_SIZE) {
      int end = Math.min(start + BATCH_SIZE, n);
      int m = end - start;

      // Mini-batch variables
      float[][] xb = new float[m][INPUT_SIZE];
      float[] yb = new float[m];
      for (int k = 0; k < m; k++) {
        int idx = indices.get(start + k);
        xb[k] = data.get(idx).features;
        yb[k] = data.get(idx).target;
      }

      // Forward pass
      float[][] pre = new float[m][HIDDEN_SIZE];
      float[][] h = new float[m][HIDDEN_SIZE];
      float[] out = new float[m];
      float[] err = new float[m];

      for (int k = 0; k < m; k++) {
        // pre = xb @ W1.T + b1 -> pre[k][i] = sum(xb[k][j] * W1[i][j]) + b1[i]
        for (int i = 0; i < HIDDEN_SIZE; i++) {
          float sum = b1[i];
          for (int j = 0; j < INPUT_SIZE; j++) {
            sum += xb[k][j] * W1[i][j];
          }
          pre[k][i] = sum;
          h[k][i] = clippedRelu(sum);
        }

        // out = h @ w2 + b2 -> out[k] = sum(h[k][i] * w2[i]) + b2
        float o = b2;
        for (int i = 0; i < HIDDEN_SIZE; i++) {
          o += h[k][i] * w2[i];
        }
        out[k] = o;

        // err = out - yb
        err[k] = o - yb[k];
        totalLoss += err[k] * err[k];
      }

      // Backward pass
      float[] d_out = new float[m];
      for (int k = 0; k < m; k++) {
        d_out[k] = err[k] / m;
      }

      // dw2 = h.T @ d_out -> dw2[i] = sum(h[k][i] * d_out[k])
      float[] dw2 = new float[HIDDEN_SIZE];
      for (int i = 0; i < HIDDEN_SIZE; i++) {
        float sum = 0;
        for (int k = 0; k < m; k++) {
          sum += h[k][i] * d_out[k];
        }
        dw2[i] = sum;
      }

      // db2 = d_out.sum()
      float db2 = 0;
      for (int k = 0; k < m; k++) {
        db2 += d_out[k];
      }

      // dh = np.outer(d_out, w2)
      // dh *= (pre >= 0.0) & (pre <= 127.0)
      float[][] dh = new float[m][HIDDEN_SIZE];
      for (int k = 0; k < m; k++) {
        for (int i = 0; i < HIDDEN_SIZE; i++) {
          float val = d_out[k] * w2[i];
          if (pre[k][i] >= 0.0f && pre[k][i] <= 127.0f) {
            dh[k][i] = val;
          } else {
            dh[k][i] = 0.0f;
          }
        }
      }

      // dw1 = dh.T @ xb -> dw1[i][j] = sum(dh[k][i] * xb[k][j])
      float[][] dw1 = new float[HIDDEN_SIZE][INPUT_SIZE];
      for (int i = 0; i < HIDDEN_SIZE; i++) {
        for (int j = 0; j < INPUT_SIZE; j++) {
          float sum = 0;
          for (int k = 0; k < m; k++) {
            sum += dh[k][i] * xb[k][j];
          }
          dw1[i][j] = sum;
        }
      }

      // db1 = dh.sum(axis=0) -> db1[i] = sum(dh[k][i])
      float[] db1 = new float[HIDDEN_SIZE];
      for (int i = 0; i < HIDDEN_SIZE; i++) {
        float sum = 0;
        for (int k = 0; k < m; k++) {
          sum += dh[k][i];
        }
        db1[i] = sum;
      }

      // Update weights
      for (int i = 0; i < HIDDEN_SIZE; i++) {
        for (int j = 0; j < INPUT_SIZE; j++) {
          W1[i][j] -= lr * dw1[i][j];
        }
        b1[i] -= lr * db1[i];
        w2[i] -= lr * dw2[i];
      }
      b2 -= lr * db2;
    }

    return totalLoss / n;
  }

  public void train(List<TrainingExample> data) {
    for (int epoch = 0; epoch < EPOCHS; epoch++) {
      float mse = trainEpoch(data, epoch);
      float lr = INITIAL_LR / (1.0f + 0.1f * epoch);
      System.out.printf("Epoch %d/%d - MSE: %.5f (lr %.5f)\n", epoch + 1, EPOCHS, mse, lr);
    }
  }

  public void exportWeights(String outputPath) throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    WeightsData data = new WeightsData();
    data.hiddenWeights = W1;
    data.hiddenBiases = b1;
    data.outputWeights = w2;
    data.outputBias = b2;

    File f = new File(outputPath);
    if (f.getParentFile() != null) {
      f.getParentFile().mkdirs();
    }
    mapper.writeValue(f, data);
  }

  public NNUEModel getModel() {
    // Deep copy arrays so modifications to trainer don't break model or vice versa
    float[][] hiddenWeightsCopy = new float[HIDDEN_SIZE][INPUT_SIZE];
    for (int i = 0; i < HIDDEN_SIZE; i++) {
      hiddenWeightsCopy[i] = Arrays.copyOf(W1[i], INPUT_SIZE);
    }
    float[] hiddenBiasesCopy = Arrays.copyOf(b1, HIDDEN_SIZE);
    float[] outputWeightsCopy = Arrays.copyOf(w2, HIDDEN_SIZE);
    float outputBiasCopy = b2;

    return new NNUEModel(hiddenWeightsCopy, hiddenBiasesCopy, outputWeightsCopy, outputBiasCopy);
  }

  // Must match format of NNUEModel.WeightsData exactly.
  public static class WeightsData {
    public float[][] hiddenWeights;
    public float[] hiddenBiases;
    public float[] outputWeights;
    public float outputBias;
  }
}
