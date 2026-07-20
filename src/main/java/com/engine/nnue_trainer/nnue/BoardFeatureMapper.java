package com.engine.nnue_trainer.nnue;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;

public class BoardFeatureMapper {

  public static float[] map(Board board, int activePlayer) {
    if (board.rows != 12 || board.cols != 12) {
      throw new IllegalArgumentException("Board must be 12x12");
    }

    int opponent = 3 - activePlayer;
    float[] vector = new float[1152];

    for (int r = 0; r < 12; r++) {
      for (int c = 0; c < 12; c++) {
        Cell cell = board.getCell(r, c);
        int stateIndex = 0; // Default to EMPTY, or unmapped

        if (cell != null) {
          if (cell.kind == CellKind.NORMAL && cell.owner == activePlayer) {
            stateIndex = 1;
          } else if (cell.kind == CellKind.NORMAL && cell.owner == opponent) {
            stateIndex = 2;
          } else if (cell.kind == CellKind.FORTIFIED && cell.owner == activePlayer) {
            stateIndex = 3;
          } else if (cell.kind == CellKind.FORTIFIED && cell.owner == opponent) {
            stateIndex = 4;
          } else if (cell.kind == CellKind.BASE && cell.owner == activePlayer) {
            stateIndex = 5;
          } else if (cell.kind == CellKind.BASE && cell.owner == opponent) {
            stateIndex = 6;
          } else if (cell.kind == CellKind.NEUTRAL) {
            stateIndex = 7;
          }
        }

        int cellIndex = r * 12 + c;
        vector[cellIndex * 8 + stateIndex] = 1.0f;
      }
    }

    return vector;
  }
}
