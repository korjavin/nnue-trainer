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
  private final float[] hiddenBias;
  private final int K;
  private final int denseSize;

  public NNUEv2Accumulator(
      Map<List<Integer>, Integer> patternDict, float[][] hiddenWeights, float[] hiddenBias, int K) {
    this(patternDict, hiddenWeights, hiddenBias, K, 14);
  }

  public NNUEv2Accumulator(
      Map<List<Integer>, Integer> patternDict,
      float[][] hiddenWeights,
      float[] hiddenBias,
      int K,
      int denseSize) {
    if (hiddenWeights != null && hiddenWeights.length > 0 && hiddenWeights[0].length != K) {
      throw new IllegalArgumentException("hiddenWeights K dimension does not match expected K");
    }
    if (hiddenBias != null && hiddenBias.length != K) {
      throw new IllegalArgumentException("hiddenBias dimension does not match expected K");
    }
    this.patternDict = patternDict;
    this.hiddenWeights = hiddenWeights;
    this.hiddenBias = hiddenBias;
    this.K = K;
    this.denseSize = denseSize;
  }

  public float[] getPatternWeights(List<Integer> pattern) {
    Integer patternId = patternDict.get(pattern);
    if (patternId != null) {
      return hiddenWeights[patternId];
    }
    return null;
  }

  public List<Integer> extractPattern(Board board, int row, int col, int perspectivePlayer) {
    Integer[] pattern = new Integer[25];
    int index = 0;

    for (int r = row - 2; r <= row + 2; r++) {
      for (int c = col - 2; c <= col + 2; c++) {
        int mDist = Math.abs(row - r) + Math.abs(col - c);

        if (board.isValidPos(r, c)) {
          Cell cell = board.getCell(r, c);
          if (cell == null) {
            pattern[index] = 12; // Out of bounds / Empty
          } else if (cell.kind == CellKind.EMPTY) {
            pattern[index] = 12;
          } else if (cell.kind == CellKind.BASE) {
            pattern[index] = 13;
          } else if (cell.kind == CellKind.NORMAL || cell.kind == CellKind.FORTIFIED) {
            if (cell.owner == perspectivePlayer) {
              pattern[index] = 0 + mDist;
            } else {
              pattern[index] = 6 + mDist;
            }
          } else if (cell.kind == CellKind.NEUTRAL) {
            pattern[index] = 14;
          } else {
            pattern[index] = 12;
          }
        } else {
          pattern[index] = 12; // Out of bounds
        }
        index++;
      }
    }
    return Arrays.asList(pattern);
  }

  public float[] computeFull(Board board, int activePlayer, float[] denseFeatures) {
    float[] accumSTM = new float[K];
    float[] accumNSTM = new float[K];

    if (hiddenBias != null) {
      System.arraycopy(hiddenBias, 0, accumSTM, 0, K);
      System.arraycopy(hiddenBias, 0, accumNSTM, 0, K);
    }

    int nstmPlayer = 3 - activePlayer;

    for (int r = 0; r < board.rows; r++) {
      for (int c = 0; c < board.cols; c++) {
        Cell cell = board.getCell(r, c);
        // Only active cells emit windows
        if (cell == null || cell.kind == CellKind.EMPTY || cell.kind == CellKind.BASE) {
          continue;
        }

        // Extract and accumulate for STM
        List<Integer> patternSTM = extractPattern(board, r, c, activePlayer);
        float[] weightsSTM = getPatternWeights(patternSTM);
        if (weightsSTM != null) {
          for (int i = 0; i < K; i++) {
            accumSTM[i] += weightsSTM[i];
          }
        }

        // Extract and accumulate for NSTM
        List<Integer> patternNSTM = extractPattern(board, r, c, nstmPlayer);
        float[] weightsNSTM = getPatternWeights(patternNSTM);
        if (weightsNSTM != null) {
          for (int i = 0; i < K; i++) {
            accumNSTM[i] += weightsNSTM[i];
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
