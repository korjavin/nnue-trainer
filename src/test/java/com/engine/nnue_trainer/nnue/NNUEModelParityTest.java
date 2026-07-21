package com.engine.nnue_trainer.nnue;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class NNUEModelParityTest {

  private static List<ParityVector> testVectors;
  private static NNUEModel model;

  public static class ParityVector {
    public float[] features;
    public float expectedScore;
  }

  @BeforeAll
  public static void setup() throws Exception {
    model = NNUEModel.createDefault();

    ObjectMapper mapper = new ObjectMapper();
    InputStream is = NNUEModelParityTest.class.getResourceAsStream("/nnue_parity_vectors.json");
    if (is == null) {
      throw new RuntimeException("Could not find /nnue_parity_vectors.json");
    }
    testVectors = mapper.readValue(is, new TypeReference<List<ParityVector>>() {});
  }

  @Test
  public void testForwardParityArray() {
    float tolerance = 1e-4f;
    for (int i = 0; i < testVectors.size(); i++) {
      ParityVector vector = testVectors.get(i);
      float actual = model.forward(vector.features);
      assertEquals(
          vector.expectedScore,
          actual,
          tolerance,
          "Mismatch for test vector " + i + " with forward(float[])");
    }
  }

  @Test
  public void testForwardParityAccumulator() {
    float tolerance = 1e-4f;
    for (int i = 0; i < testVectors.size(); i++) {
      ParityVector vector = testVectors.get(i);

      Accumulator acc = new Accumulator();
      // Emulate accumulator initialization manually from features array
      float[][] hiddenWeights = model.getHiddenWeights();
      float[] hiddenBiases = model.getHiddenBiases();

      float[] accum = new float[256];
      System.arraycopy(hiddenBiases, 0, accum, 0, hiddenBiases.length);

      for (int h = 0; h < accum.length; h++) {
        float sum = 0;
        for (int j = 0; j < 864; j++) {
          if (vector.features[j] != 0.0f) {
            sum += hiddenWeights[h][j] * vector.features[j];
          }
        }
        accum[h] += sum;
      }

      // Inject accum state
      Accumulator testAcc = new Accumulator(accum);
      float actual = model.forward(testAcc);

      assertEquals(
          vector.expectedScore,
          actual,
          tolerance,
          "Mismatch for test vector " + i + " with forward(Accumulator)");
    }
  }
}
