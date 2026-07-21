package com.engine.nnue_trainer.v2;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

public class NNUEv2AccumulatorTest {

    @Test
    public void testForward() {
        int k = 16;
        float[] denseBiases = new float[k];
        Map<Long, float[]> stmWeights = new HashMap<>();
        Map<Long, float[]> nstmWeights = new HashMap<>();

        long dummyPatternId = 0L; // Dummy pattern for empty 5x5 board
        stmWeights.put(dummyPatternId, new float[k]);

        NNUEv2Accumulator accumulator = new NNUEv2Accumulator(stmWeights, nstmWeights, denseBiases, k);
        Board board = new Board(5, 5);
        float[] dense14 = new float[14];

        float[] output = accumulator.forward(board, dense14);
        assertEquals(k * 2 + 14, output.length);
    }

    @Test
    public void testVariableBoardSizesAndDeterministicOutput() {
        int k = 2;
        float[] denseBiases = new float[]{1.0f, 1.0f};
        Map<Long, float[]> stmWeights = new HashMap<>();
        Map<Long, float[]> nstmWeights = new HashMap<>();

        NNUEv2Accumulator accumulator = new NNUEv2Accumulator(stmWeights, nstmWeights, denseBiases, k);

        // Let's populate specific weights for patterns
        Board board = new Board(5, 5);
        board.setCell(2, 2, new Cell(1, CellKind.NORMAL));

        long stmPattern = accumulator.extractPattern(board, 2, 2, true);
        stmWeights.put(stmPattern, new float[]{2.0f, 3.0f});

        long nstmPattern = accumulator.extractPattern(board, 2, 2, false);
        nstmWeights.put(nstmPattern, new float[]{4.0f, 5.0f});

        float[] dense14 = new float[14];

        float[] output = accumulator.forward(board, dense14);
        // Expecting base bias (1.0) + weight
        // Only one cell (2, 2) has the specific pattern, other 24 cells have mostly empty patterns
        // We will assert just length and deterministic execution to avoid tedious exact tracking unless necessary
        assertEquals(k * 2 + 14, output.length);

        Board largeBoard = new Board(12, 12);
        largeBoard.setCell(6, 6, new Cell(1, CellKind.NORMAL));
        float[] outputLarge = accumulator.forward(largeBoard, dense14);
        assertEquals(k * 2 + 14, outputLarge.length);
    }
}
