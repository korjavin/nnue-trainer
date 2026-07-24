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
  public void testIncrementalAccumulatorParity() {
    Random random = new Random(42);
    int K = 16;
    int denseSize = 14;
    int rows = 8;
    int cols = 8;

    // Auto-populating dictionary for the test
    Map<List<Integer>, Integer> patternDict = new HashMap<>();
    List<float[]> weightsList = new ArrayList<>();

    float[] hiddenBias = new float[K];
    for (int i = 0; i < K; i++) hiddenBias[i] = random.nextFloat();

    NNUEv2Accumulator acc =
        new NNUEv2Accumulator(patternDict, new float[0][0], hiddenBias, K, denseSize) {
          @Override
          public float[] getPatternWeights(List<Integer> pattern) {
            if (!patternDict.containsKey(pattern)) {
              patternDict.put(pattern, weightsList.size());
              float[] w = new float[K];
              for (int i = 0; i < K; i++) w[i] = random.nextFloat();
              weightsList.add(w);
            }
            return weightsList.get(patternDict.get(pattern));
          }
        };

    Board board = new Board(rows, cols);
    board.setCell(0, 0, new Cell(1, CellKind.BASE));
    board.setCell(7, 7, new Cell(2, CellKind.BASE));

    int activePlayer = 1;

    for (int step = 0; step < 100; step++) {
      Board oldBoard = copyBoard(board);

      int numMods = 1 + random.nextInt(2);
      List<Pos> mods = new ArrayList<>();

      for (int i = 0; i < numMods; i++) {
        int r = random.nextInt(rows);
        int c = random.nextInt(cols);
        if (board.getCell(r, c).kind == CellKind.BASE) continue;
        int owner = 1 + random.nextInt(2);
        CellKind kind = CellKind.values()[random.nextInt(5)];
        board.setCell(r, c, new Cell(owner, kind));
        mods.add(new Pos(r, c));
      }

      activePlayer = 3 - activePlayer;

      // Pre-populate dictionary for both old and new boards
      populateDict(acc, oldBoard, activePlayer);
      populateDict(acc, board, activePlayer);

      // Full compute old
      float[] fullOld = acc.computeFull(oldBoard, activePlayer, null);
      float[] accumStmOld = Arrays.copyOfRange(fullOld, 0, K);
      float[] accumNstmOld = Arrays.copyOfRange(fullOld, K, 2 * K);

      // Full compute new
      float[] fullNew = acc.computeFull(board, activePlayer, null);
      float[] expectedStm = Arrays.copyOfRange(fullNew, 0, K);
      float[] expectedNstm = Arrays.copyOfRange(fullNew, K, 2 * K);

      // Incremental
      float[] incStm = Arrays.copyOf(accumStmOld, K);
      float[] incNstm = Arrays.copyOf(accumNstmOld, K);

      NNUEv2IncrementalAccumulator.updateAccumulator(
          acc, oldBoard, board, mods, incStm, incNstm, activePlayer);

      assertArrayEquals(expectedStm, incStm, 1e-4f, "STM differs at step " + step);
      assertArrayEquals(expectedNstm, incNstm, 1e-4f, "NSTM differs at step " + step);
    }
  }

  private void populateDict(NNUEv2Accumulator acc, Board b, int activePlayer) {
    for (int r = 0; r < b.rows; r++) {
      for (int c = 0; c < b.cols; c++) {
        Cell cell = b.getCell(r, c);
        if (cell != null && cell.kind != CellKind.EMPTY && cell.kind != CellKind.BASE) {
          acc.getPatternWeights(acc.extractPattern(b, r, c, activePlayer));
          acc.getPatternWeights(acc.extractPattern(b, r, c, 3 - activePlayer));
        }
      }
    }
  }

  private Board copyBoard(Board b) {
    Board copy = new Board(b.rows, b.cols);
    for (int r = 0; r < b.rows; r++) {
      for (int c = 0; c < b.cols; c++) {
        Cell cell = b.getCell(r, c);
        copy.setCell(r, c, new Cell(cell.owner, cell.kind));
      }
    }
    return copy;
  }
}
