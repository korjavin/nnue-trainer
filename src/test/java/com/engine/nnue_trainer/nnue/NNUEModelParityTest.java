package com.engine.nnue_trainer.nnue;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

public class NNUEModelParityTest {

  public static class ParityVector {
    public float[] features;
    public float expectedOutput;
  }

  @Test
  public void testForwardParity() throws Exception {
    NNUEModel model = NNUEModel.createDefault();

    ObjectMapper mapper = new ObjectMapper();
    InputStream is = getClass().getResourceAsStream("/nnue_parity_vectors.json");
    if (is == null) {
      throw new RuntimeException("Could not find /nnue_parity_vectors.json");
    }

    List<ParityVector> vectors = mapper.readValue(is, new TypeReference<List<ParityVector>>() {});

    for (int i = 0; i < vectors.size(); i++) {
      ParityVector vector = vectors.get(i);
      float javaOutput = model.forward(vector.features);
      assertEquals(vector.expectedOutput, javaOutput, 1e-4, "Mismatch on vector " + i);
    }
  }

  @Test
  public void testForwardAccumulatorParity() throws Exception {
    NNUEModel model = NNUEModel.createDefault();

    ObjectMapper mapper = new ObjectMapper();
    InputStream is = getClass().getResourceAsStream("/nnue_parity_vectors.json");
    if (is == null) {
      throw new RuntimeException("Could not find /nnue_parity_vectors.json");
    }

    List<ParityVector> vectors = mapper.readValue(is, new TypeReference<List<ParityVector>>() {});

    for (int i = 0; i < vectors.size(); i++) {
      ParityVector vector = vectors.get(i);

      Accumulator accumulator = new Accumulator(model.getHiddenBiases());
      float[] accumArr = accumulator.getAccum();
      float[][] hiddenWeights = model.getHiddenWeights();

      for (int j = 0; j < vector.features.length; j++) {
        if (vector.features[j] == 1.0f) {
          for (int k = 0; k < accumArr.length; k++) {
            accumArr[k] += hiddenWeights[k][j];
          }
        }
      }

      float javaOutput = model.forward(accumulator);
      assertEquals(
          vector.expectedOutput, javaOutput, 1e-4, "Mismatch on vector " + i + " via accumulator");
    }
  }
}
