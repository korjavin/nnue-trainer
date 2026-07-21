package com.engine.nnue_trainer.v2;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class NNUEv2Accumulator {
  private final Map<List<Integer>, Integer> patternDict;
  private final float[][] hiddenWeights;
  private final int K;
  private final int denseSize;

  public NNUEv2Accumulator(
      Map<List<Integer>, Integer> patternDict, float[][] hiddenWeights, int K) {
    this(patternDict, hiddenWeights, K, 14);
  }

  public NNUEv2Accumulator(
      Map<List<Integer>, Integer> patternDict, float[][] hiddenWeights, int K, int denseSize) {
    this.patternDict = patternDict;
    this.hiddenWeights = hiddenWeights;
    this.K = K;
    this.denseSize = denseSize;
  }

  public List<Integer> extractPattern(Board board, int row, int col, int perspectivePlayer) {
    Integer[] pattern = new Integer[25];
    int index = 0;

    for (int r = row - 2; r <= row + 2; r++) {
      for (int c = col - 2; c <= col + 2; c++) {
        if (board.isValidPos(r, c)) {
          Cell cell = board.getCell(r, c);
          if (cell == null) {
            pattern[index] = 0;
          } else if (cell.kind == CellKind.EMPTY || cell.kind == CellKind.BASE) {
            pattern[index] = 0;
          } else if (cell.kind == CellKind.NORMAL) {
            pattern[index] = cell.owner == perspectivePlayer ? 1 : 2;
          } else if (cell.kind == CellKind.FORTIFIED) {
            pattern[index] = cell.owner == perspectivePlayer ? 3 : 4;
          } else if (cell.kind == CellKind.NEUTRAL) {
            pattern[index] = 5;
          } else {
            pattern[index] = 0;
          }
        } else {
          pattern[index] = 0;
        }
        index++;
      }
    }
    return Arrays.asList(pattern);
  }

  public float[] computeFull(Board board, int activePlayer, float[] denseFeatures) {
    float[] accumSTM = new float[K];
    float[] accumNSTM = new float[K];

    int nstmPlayer = 3 - activePlayer;

    for (int r = 0; r < board.rows; r++) {
      for (int c = 0; c < board.cols; c++) {
        // Extract and accumulate for STM
        List<Integer> patternSTM = extractPattern(board, r, c, activePlayer);
        Integer patternIdSTM = patternDict.get(patternSTM);
        if (patternIdSTM != null) {
          for (int i = 0; i < K; i++) {
            accumSTM[i] += hiddenWeights[patternIdSTM][i];
          }
        }

        // Extract and accumulate for NSTM
        List<Integer> patternNSTM = extractPattern(board, r, c, nstmPlayer);
        Integer patternIdNSTM = patternDict.get(patternNSTM);
        if (patternIdNSTM != null) {
          for (int i = 0; i < K; i++) {
            accumNSTM[i] += hiddenWeights[patternIdNSTM][i];
          }
        }
      }
    }

    if (denseFeatures == null) {
      denseFeatures = new float[denseSize];
    }

    float[] result = new float[K * 2 + denseSize];
    System.arraycopy(accumSTM, 0, result, 0, K);
    System.arraycopy(accumNSTM, 0, result, K, K);
    System.arraycopy(denseFeatures, 0, result, K * 2, denseSize);

    return result;
  }
}
