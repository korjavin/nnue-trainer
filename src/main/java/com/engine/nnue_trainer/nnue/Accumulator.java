package com.engine.nnue_trainer.nnue;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import java.util.Arrays;

public class Accumulator {
  private float[] accum;

  public Accumulator() {
    this.accum = new float[256];
  }

  public Accumulator(float[] initial) {
    this.accum = Arrays.copyOf(initial, initial.length);
  }

  public void init(Board board, int perspectivePlayer, NNUEModel model) {
    float[][] hiddenWeights = model.getHiddenWeights();
    float[] hiddenBiases = model.getHiddenBiases();

    this.accum = Arrays.copyOf(hiddenBiases, hiddenBiases.length);

    float[] features = BoardFeatureMapper.map(board, perspectivePlayer);
    for (int i = 0; i < accum.length; i++) {
      float sum = 0;
      for (int j = 0; j < 864; j++) {
        if (features[j] != 0.0f) {
          sum += hiddenWeights[i][j];
        }
      }
      accum[i] += sum;
    }
  }

  public void update(int row, int col, int oldState, int newState, NNUEModel model) {
    if (oldState == newState) return;

    float[][] hiddenWeights = model.getHiddenWeights();
    int cellIndex = row * 12 + col;

    for (int i = 0; i < accum.length; i++) {
      // oldState 0 is also mapped to cellIndex * 6 + 0
      accum[i] -= hiddenWeights[i][cellIndex * 6 + oldState];
      accum[i] += hiddenWeights[i][cellIndex * 6 + newState];
    }
  }

  public float[] getClippedReLUActivation() {
    float[] activation = new float[accum.length];
    for (int i = 0; i < accum.length; i++) {
      activation[i] = Math.max(0.0f, Math.min(127.0f, accum[i]));
    }
    return activation;
  }

  public float[] getAccum() {
    return accum;
  }

  public Accumulator copy() {
    return new Accumulator(this.accum);
  }

  public static void computeDiff(
      Board oldBoard, Board newBoard, Accumulator accumulator, int activePlayer, NNUEModel model) {
    for (int r = 0; r < oldBoard.rows; r++) {
      for (int c = 0; c < oldBoard.cols; c++) {
        Cell oldCell = oldBoard.getCell(r, c);
        Cell newCell = newBoard.getCell(r, c);
        int oldState = getFeatureState(oldCell, activePlayer);
        int newState = getFeatureState(newCell, activePlayer);
        accumulator.update(r, c, oldState, newState, model);
      }
    }
  }

  public static int getFeatureState(Cell cell, int activePlayer) {
    if (cell == null) return 0;
    int opponent = 3 - activePlayer;
    if (cell.kind == CellKind.NORMAL) {
      if (cell.owner == activePlayer) return 1;
      if (cell.owner == opponent) return 2;
    } else if (cell.kind == CellKind.FORTIFIED) {
      if (cell.owner == activePlayer) return 3;
      if (cell.owner == opponent) return 4;
    } else if (cell.kind == CellKind.NEUTRAL) {
      return 5;
    }
    return 0; // BASE and EMPTY and unmapped map to 0
  }
}
