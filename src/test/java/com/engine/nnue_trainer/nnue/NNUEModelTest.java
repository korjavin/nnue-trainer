package com.engine.nnue_trainer.nnue;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class NNUEModelTest {

    @Test
    public void testForwardWithDefaultWeights() {
        NNUEModel model = NNUEModel.createDefault();
        float[] input = new float[104];

        // Input of 0s, output should be outputBias
        float output = model.forward(input);
        assertEquals(0.0f, output);
    }

    @Test
    public void testForwardWithCustomWeights() {
        int inputSize = 104;
        int hiddenSize = 256;
        float[][] hiddenWeights = new float[hiddenSize][inputSize];
        float[] hiddenBiases = new float[hiddenSize];
        float[] outputWeights = new float[hiddenSize];
        float outputBias = 10.0f;

        // Give bias to hidden nodes
        for (int i = 0; i < hiddenSize; i++) {
            hiddenBiases[i] = 1.0f;
            outputWeights[i] = 2.0f;
        }

        NNUEModel model = new NNUEModel(hiddenWeights, hiddenBiases, outputWeights, outputBias);
        float[] input = new float[104];

        // Output should be:
        // sum(hidden_layer) = 256 nodes, each with bias 1.0 -> 1.0
        // after ReLU: 1.0
        // output layer: 256 * (2.0 * 1.0) + 10.0 = 512.0 + 10.0 = 522.0
        float output = model.forward(input);
        assertEquals(522.0f, output);
    }

    @Test
    public void testForwardWithClippedRelu() {
        int inputSize = 104;
        int hiddenSize = 256;
        float[][] hiddenWeights = new float[hiddenSize][inputSize];
        float[] hiddenBiases = new float[hiddenSize];
        float[] outputWeights = new float[hiddenSize];
        float outputBias = 0.0f;

        // Hidden bias > 127
        hiddenBiases[0] = 200.0f;
        outputWeights[0] = 1.0f;

        // Hidden bias < 0
        hiddenBiases[1] = -50.0f;
        outputWeights[1] = 1.0f;

        NNUEModel model = new NNUEModel(hiddenWeights, hiddenBiases, outputWeights, outputBias);
        float[] input = new float[104];

        // Node 0 should be clipped to 127.0
        // Node 1 should be clipped to 0.0
        // Output = 127.0 * 1.0 + 0.0 * 1.0 = 127.0
        float output = model.forward(input);
        assertEquals(127.0f, output);
    }

    @Test
    public void testInvalidInputLength() {
        NNUEModel model = NNUEModel.createDefault();
        float[] input = new float[100];

        assertThrows(IllegalArgumentException.class, () -> {
            model.forward(input);
        });
    }
}
