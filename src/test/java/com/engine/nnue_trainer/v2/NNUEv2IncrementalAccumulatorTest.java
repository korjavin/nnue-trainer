package com.engine.nnue_trainer.v2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.board.Pos;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

public class NNUEv2IncrementalAccumulatorTest {

  @Test
  public void testParity() {
    int rows = 12;
    int cols = 12;
    int K = 16;
    int denseSize = 14;
    Random random = new Random(42);

    Map<List<Integer>, Integer> patternDict = new HashMap<>();
    for (int i = 0; i < 100; i++) {
      Integer[] pattern = new Integer[25];
      for (int j = 0; j < 25; j++) {
        pattern[j] = random.nextInt(16);
      }
      List<Integer> listPattern = Arrays.asList(pattern);
      if (!patternDict.containsKey(listPattern)) {
        patternDict.put(listPattern, patternDict.size());
      }
    }

    Integer[] dummyPattern = new Integer[25];
    Arrays.fill(dummyPattern, 12);
    patternDict.put(Arrays.asList(dummyPattern), patternDict.size());

    int numPatterns = patternDict.size();
    float[][] hiddenWeights = new float[numPatterns][K];
    for (int i = 0; i < numPatterns; i++) {
      for (int j = 0; j < K; j++) {
        hiddenWeights[i][j] = random.nextFloat();
      }
    }

    float[] hiddenBias = new float[K];
    for (int j = 0; j < K; j++) {
      hiddenBias[j] = random.nextFloat();
    }

    NNUEv2Accumulator fullAcc =
        new NNUEv2Accumulator(patternDict, hiddenWeights, hiddenBias, K, denseSize);
    NNUEv2IncrementalAccumulator incAcc = new NNUEv2IncrementalAccumulator(fullAcc, rows, cols);

    Board board = new Board(rows, cols);
    for (int i = 0; i < 20; i++) {
      int r = random.nextInt(rows);
      int c = random.nextInt(cols);
      int owner = random.nextBoolean() ? 1 : 2;
      CellKind kind = CellKind.values()[random.nextInt(CellKind.values().length)];
      board.setCell(r, c, new Cell(owner, kind));
    }

    int activePlayer = 1;
    incAcc.initialize(board, activePlayer);

    for (int step = 0; step < 100; step++) {
      int numChanges = random.nextInt(3) + 1;
      List<Pos> modifiedCells = new ArrayList<>();
      for (int i = 0; i < numChanges; i++) {
        int r = random.nextInt(rows);
        int c = random.nextInt(cols);
        int owner = random.nextBoolean() ? 1 : 2;
        CellKind kind = CellKind.values()[random.nextInt(CellKind.values().length)];
        board.setCell(r, c, new Cell(owner, kind));
        modifiedCells.add(new Pos(r, c));
      }

      if (random.nextFloat() < 0.3f) {
        activePlayer = 3 - activePlayer;
      }

      float[] denseFeatures = new float[denseSize];
      for (int i = 0; i < denseSize; i++) {
        denseFeatures[i] = random.nextFloat();
      }

      float[] resFull = fullAcc.computeFull(board, activePlayer, denseFeatures);
      float[] resInc = incAcc.update(board, activePlayer, modifiedCells, denseFeatures);

      assertArrayEquals(resFull, resInc, 1e-4f, "Parity failed at step " + step);
    }
  }
}
