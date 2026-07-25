package com.engine.nnue_trainer.v2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class NNUEv2AccumulatorTest {

  @Test
  public void testAccumulatorOutputVariableSize() {
    // Mock a 5x5 pattern dictionary
    Map<List<Integer>, Integer> patternDict = new HashMap<>();

    Integer[] pattern1Array = new Integer[25];
    Arrays.fill(pattern1Array, 12);
    pattern1Array[12] = 0; // Center is STM normal (dist 0) -> 0 + 0 = 0
    pattern1Array[24] = 10; // (4,4) is NSTM relative to (2,2) for P1 (dist 4) -> 6 + 4 = 10
    List<Integer> pattern1 = Arrays.asList(pattern1Array);
    patternDict.put(pattern1, 0);

    Integer[] pattern2Array = new Integer[25];
    Arrays.fill(pattern2Array, 12);
    pattern2Array[0] = 10; // (2,2) is NSTM relative to (4,4) for P1 (dist 4) -> 6 + 4 = 10
    pattern2Array[12] =
        0; // Center is STM normal (dist 0) for P2 -> wait, NSTM center? P1 extracts for NSTM (P2).
    // For P2, (4,4) is their own piece. STM normal (dist 0) is 0
    List<Integer> pattern2 = Arrays.asList(pattern2Array);
    patternDict.put(pattern2, 1);

    // extractPattern for P1 at (4,4):
    // cell at (2,2) is P1 (perspective P1 -> STM) -> 0+4 = 4
    // cell at (4,4) is P2 (perspective P1 -> NSTM) -> 6+0 = 6
    pattern2Array[0] = 4;
    pattern2Array[12] = 6;
    patternDict.put(pattern2, 1);

    // extractPattern for P2 at (2,2):
    // cell at (2,2) is P1 (perspective P2 -> NSTM) -> 6+0 = 6
    // cell at (4,4) is P2 (perspective P2 -> STM) -> 0+4 = 4
    Integer[] pattern3Array = new Integer[25];
    Arrays.fill(pattern3Array, 12);
    pattern3Array[12] = 6;
    pattern3Array[24] = 4;
    List<Integer> pattern3 = Arrays.asList(pattern3Array);
    patternDict.put(pattern3, 2);

    // extractPattern for P2 at (4,4):
    // cell at (2,2) is P1 (perspective P2 -> NSTM) -> 6+4 = 10
    // cell at (4,4) is P2 (perspective P2 -> STM) -> 0+0 = 0
    Integer[] pattern4Array = new Integer[25];
    Arrays.fill(pattern4Array, 12);
    pattern4Array[0] = 10;
    pattern4Array[12] = 0;
    List<Integer> pattern4 = Arrays.asList(pattern4Array);
    patternDict.put(pattern4, 3);

    int K = 2; // Accumulator size
    int denseSize = 3;

    // Weights: [pattern_id][k]
    float[][] hiddenWeights = new float[4][K];
    hiddenWeights[0] = new float[] {1.0f, 2.0f};
    hiddenWeights[1] = new float[] {3.0f, 4.0f};
    hiddenWeights[2] = new float[] {5.0f, 6.0f};
    hiddenWeights[3] = new float[] {7.0f, 8.0f};

    float[] hiddenBias = new float[] {100.0f, 200.0f};

    NNUEv2Accumulator accumulator =
        new NNUEv2Accumulator(patternDict, hiddenWeights, hiddenBias, K, denseSize);

    // Variable size board: 6x6
    Board board = new Board(6, 6);
    board.setCell(2, 2, new Cell(1, CellKind.NORMAL)); // Active player 1
    board.setCell(4, 4, new Cell(2, CellKind.NORMAL)); // Active player 2

    float[] denseFeatures = new float[] {0.1f, 0.2f, 0.3f};

    float[] result = accumulator.computeFull(board, 1, denseFeatures);

    // Assert size is K * 2 + denseSize = 2*2 + 3 = 7
    assertEquals(7, result.length);

    // patternSTM at (2,2) for P1 is pattern 0 -> {1.0, 2.0}
    // patternSTM at (4,4) for P1 is pattern 1 -> {3.0, 4.0}
    // accumSTM = {1.0 + 3.0, 2.0 + 4.0} = {4.0, 6.0}

    // patternNSTM at (2,2) for P2 is pattern 2 -> {5.0, 6.0}
    // patternNSTM at (4,4) for P2 is pattern 3 -> {7.0, 8.0}
    // accumNSTM = {5.0 + 7.0 + 100.0, 6.0 + 8.0 + 200.0} = {112.0, 214.0}

    float[] expected = new float[] {104.0f, 206.0f, 112.0f, 214.0f, 0.1f, 0.2f, 0.3f};
    assertArrayEquals(expected, result, 1e-5f);
  }
}
