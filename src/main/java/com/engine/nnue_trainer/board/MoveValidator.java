package com.engine.nnue_trainer.board;

public class MoveValidator {
  public static boolean isValidMove(int player, Pos target, Board board) {
    if (!board.isValidPos(target.row, target.col)) {
      return false;
    }

    Cell targetCell = board.getCell(target.row, target.col);
    if (targetCell.kind != CellKind.EMPTY) {
      if (targetCell.kind != CellKind.NORMAL || targetCell.owner == player) {
        return false;
      }
    }

    boolean[] connected = BaseConnectionSearch.connected(player, board);

    for (int dr = -1; dr <= 1; dr++) {
      for (int dc = -1; dc <= 1; dc++) {
        if (dr == 0 && dc == 0) continue;
        int r = target.row + dr;
        int c = target.col + dc;
        if (board.isValidPos(r, c)) {
          int index = r * board.cols + c;
          if (connected[index]) {
            return true;
          }
        }
      }
    }

    return false;
  }
}
