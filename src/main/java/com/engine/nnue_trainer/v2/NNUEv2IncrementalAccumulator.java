package com.engine.nnue_trainer.v2;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.board.Pos;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class NNUEv2IncrementalAccumulator {
  private final NNUEv2Accumulator fullAccum;
  private final int rows;
  private final int cols;

  private final Map<List<Integer>, Integer> patternDict;
  private final float[][] hiddenWeights;
  private final float[] hiddenBias;
  private final int K;
  private final int denseSize;

  private Integer[][] cachedStm;
  private Integer[][] cachedNstm;
  private float[] accStm;
  private float[] accNstm;
  private int activePlayer;

  @SuppressWarnings("unchecked")
  public NNUEv2IncrementalAccumulator(NNUEv2Accumulator fullAccum, int rows, int cols) {
    this.fullAccum = fullAccum;
    this.rows = rows;
    this.cols = cols;

    // Extract fields using reflection since they are private and we want to avoid changing
    // NNUEv2Accumulator API if possible.
    try {
      Field kField = NNUEv2Accumulator.class.getDeclaredField("K");
      kField.setAccessible(true);
      this.K = kField.getInt(fullAccum);

      Field denseSizeField = NNUEv2Accumulator.class.getDeclaredField("denseSize");
      denseSizeField.setAccessible(true);
      this.denseSize = denseSizeField.getInt(fullAccum);

      Field pdField = NNUEv2Accumulator.class.getDeclaredField("patternDict");
      pdField.setAccessible(true);
      this.patternDict = (Map<List<Integer>, Integer>) pdField.get(fullAccum);

      Field hwField = NNUEv2Accumulator.class.getDeclaredField("hiddenWeights");
      hwField.setAccessible(true);
      this.hiddenWeights = (float[][]) hwField.get(fullAccum);

      Field hbField = NNUEv2Accumulator.class.getDeclaredField("hiddenBias");
      hbField.setAccessible(true);
      this.hiddenBias = (float[]) hbField.get(fullAccum);
    } catch (Exception e) {
      throw new RuntimeException("Failed to initialize incremental accumulator via reflection", e);
    }

    this.cachedStm = new Integer[rows][cols];
    this.cachedNstm = new Integer[rows][cols];
    this.accStm = new float[K];
    this.accNstm = new float[K];
    this.activePlayer = 1;
  }

  public void initialize(Board board, int activePlayer) {
    this.activePlayer = activePlayer;
    if (hiddenBias != null) {
      System.arraycopy(hiddenBias, 0, this.accStm, 0, K);
      System.arraycopy(hiddenBias, 0, this.accNstm, 0, K);
    } else {
      for (int i = 0; i < K; i++) {
        this.accStm[i] = 0;
        this.accNstm[i] = 0;
      }
    }

    for (int r = 0; r < rows; r++) {
      for (int c = 0; c < cols; c++) {
        this.cachedStm[r][c] = null;
        this.cachedNstm[r][c] = null;
      }
    }

    int nstmPlayer = 3 - activePlayer;

    for (int r = 0; r < rows; r++) {
      for (int c = 0; c < cols; c++) {
        Cell cell = board.getCell(r, c);
        if (cell == null || cell.kind == CellKind.EMPTY || cell.kind == CellKind.BASE) {
          continue;
        }

        List<Integer> patternStm = fullAccum.extractPattern(board, r, c, activePlayer);
        Integer idStm = patternDict.get(patternStm);
        if (idStm != null) {
          this.cachedStm[r][c] = idStm;
          for (int i = 0; i < K; i++) {
            this.accStm[i] += hiddenWeights[idStm][i];
          }
        }

        List<Integer> patternNstm = fullAccum.extractPattern(board, r, c, nstmPlayer);
        Integer idNstm = patternDict.get(patternNstm);
        if (idNstm != null) {
          this.cachedNstm[r][c] = idNstm;
          for (int i = 0; i < K; i++) {
            this.accNstm[i] += hiddenWeights[idNstm][i];
          }
        }
      }
    }
  }

  private void swapPlayers() {
    this.activePlayer = 3 - this.activePlayer;
    Integer[][] tempCache = this.cachedStm;
    this.cachedStm = this.cachedNstm;
    this.cachedNstm = tempCache;

    float[] tempAcc = this.accStm;
    this.accStm = this.accNstm;
    this.accNstm = tempAcc;
  }

  public float[] update(
      Board board, int activePlayer, List<Pos> modifiedCells, float[] denseFeatures) {
    if (activePlayer != this.activePlayer) {
      swapPlayers();
    }

    Set<Pos> affectedWindows = new HashSet<>();
    for (Pos p : modifiedCells) {
      for (int dr = -2; dr <= 2; dr++) {
        for (int dc = -2; dc <= 2; dc++) {
          int wr = p.row + dr;
          int wc = p.col + dc;
          if (wr >= 0 && wr < rows && wc >= 0 && wc < cols) {
            affectedWindows.add(new Pos(wr, wc));
          }
        }
      }
    }

    int nstmPlayer = 3 - activePlayer;

    for (Pos w : affectedWindows) {
      int r = w.row;
      int c = w.col;

      // Subtract old
      Integer oldStmId = cachedStm[r][c];
      if (oldStmId != null) {
        for (int i = 0; i < K; i++) {
          accStm[i] -= hiddenWeights[oldStmId][i];
        }
        cachedStm[r][c] = null;
      }

      Integer oldNstmId = cachedNstm[r][c];
      if (oldNstmId != null) {
        for (int i = 0; i < K; i++) {
          accNstm[i] -= hiddenWeights[oldNstmId][i];
        }
        cachedNstm[r][c] = null;
      }

      // Add new
      Cell cell = board.getCell(r, c);
      if (cell != null && cell.kind != CellKind.EMPTY && cell.kind != CellKind.BASE) {
        List<Integer> patternStm = fullAccum.extractPattern(board, r, c, activePlayer);
        Integer newStmId = patternDict.get(patternStm);
        if (newStmId != null) {
          cachedStm[r][c] = newStmId;
          for (int i = 0; i < K; i++) {
            accStm[i] += hiddenWeights[newStmId][i];
          }
        }

        List<Integer> patternNstm = fullAccum.extractPattern(board, r, c, nstmPlayer);
        Integer newNstmId = patternDict.get(patternNstm);
        if (newNstmId != null) {
          cachedNstm[r][c] = newNstmId;
          for (int i = 0; i < K; i++) {
            accNstm[i] += hiddenWeights[newNstmId][i];
          }
        }
      }
    }

    if (denseFeatures == null) {
      denseFeatures = new float[denseSize];
    }

    float[] result = new float[K * 2 + denseSize];
    System.arraycopy(accStm, 0, result, 0, K);
    System.arraycopy(accNstm, 0, result, K, K);
    System.arraycopy(denseFeatures, 0, result, K * 2, denseSize);

    return result;
  }
}
