package com.engine.nnue_trainer.nnue;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;

public class BoardFeatureMapper {

  public static float[] map(Board board) {
    if (board.rows != 12 || board.cols != 12) {
      throw new IllegalArgumentException("Board must be 12x12");
    }

    float[] vector = new float[2016];

    for (int r = 0; r < 12; r++) {
      for (int c = 0; c < 12; c++) {
        Cell cell = board.getCell(r, c);
        int stateIndex = 0; // Default to EMPTY

        if (cell != null) {
          if (cell.kind == CellKind.EMPTY) {
            stateIndex = 0;
          } else if (cell.kind == CellKind.NORMAL && cell.owner >= 1 && cell.owner <= 4) {
            stateIndex = cell.owner;
          } else if (cell.kind == CellKind.BASE && cell.owner >= 1 && cell.owner <= 4) {
            stateIndex = 4 + cell.owner;
          } else if (cell.kind == CellKind.FORTIFIED && cell.owner >= 1 && cell.owner <= 4) {
            stateIndex = 8 + cell.owner;
          } else if (cell.kind == CellKind.NEUTRAL) {
            stateIndex = 13;
          }
        }

        int cellIndex = r * 12 + c;
        vector[cellIndex * 14 + stateIndex] = 1.0f;
      }
    }

    return vector;
  }
}
