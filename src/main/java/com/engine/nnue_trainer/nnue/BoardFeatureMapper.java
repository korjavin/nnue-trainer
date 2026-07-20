package com.engine.nnue_trainer.nnue;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;

public class BoardFeatureMapper {

  public static float[] map(Board board, int activePlayer) {
    if (board.rows != 12 || board.cols != 12) {
      throw new IllegalArgumentException("Board must be 12x12");
    }

    float[] vector = new float[864];
    int opponent = 3 - activePlayer;

    for (int r = 0; r < 12; r++) {
      for (int c = 0; c < 12; c++) {
        Cell cell = board.getCell(r, c);
        int stateIndex = 0; // Default to EMPTY or unmapped state

        if (cell != null) {
          if (cell.kind == CellKind.NORMAL) {
            if (cell.owner == activePlayer) {
              stateIndex = 1;
            } else if (cell.owner == opponent) {
              stateIndex = 2;
            }
          } else if (cell.kind == CellKind.FORTIFIED) {
            if (cell.owner == activePlayer) {
              stateIndex = 3;
            } else if (cell.owner == opponent) {
              stateIndex = 4;
            }
          } else if (cell.kind == CellKind.NEUTRAL) {
            stateIndex = 5;
          }
        }

        int cellIndex = r * 12 + c;
        vector[cellIndex * 6 + stateIndex] = 1.0f;
      }
    }

    return vector;
  }
}
