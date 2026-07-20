package com.engine.nnue_trainer.board;

import java.util.LinkedList;
import java.util.Queue;

public class BaseConnectionSearch {

  // 8 directions (vertical, horizontal, diagonal)
  private static final int[] DROW = {-1, -1, -1, 0, 0, 1, 1, 1};
  private static final int[] DCOL = {-1, 0, 1, -1, 1, -1, 0, 1};

  public static boolean[] connected(int player, Board board) {
    boolean[] result = new boolean[board.rows * board.cols];

    // Find player's base
    int baseRow = -1;
    int baseCol = -1;

    if (player == 1) {
      baseRow = 0;
      baseCol = 0;
    } else if (player == 2) {
      baseRow = board.rows - 1;
      baseCol = board.cols - 1;
    } else if (player == 3) {
      baseRow = 0;
      baseCol = board.cols - 1;
    } else if (player == 4) {
      baseRow = board.rows - 1;
      baseCol = 0;
    } else {
      return result; // Invalid player
    }

    Cell baseCell = board.getCell(baseRow, baseCol);
    if (baseCell == null || baseCell.owner != player) {
      return result; // Base cell is not owned by the player, thus nothing is connected
    }

    Queue<Pos> queue = new LinkedList<>();
    queue.add(new Pos(baseRow, baseCol));
    result[baseRow * board.cols + baseCol] = true;

    while (!queue.isEmpty()) {
      Pos current = queue.poll();

      for (int i = 0; i < 8; i++) {
        int newRow = current.row + DROW[i];
        int newCol = current.col + DCOL[i];

        if (board.isValidPos(newRow, newCol)) {
          int index = newRow * board.cols + newCol;
          if (!result[index]) {
            Cell neighborCell = board.getCell(newRow, newCol);
            if (neighborCell != null && neighborCell.owner == player) {
              result[index] = true;
              queue.add(new Pos(newRow, newCol));
            }
          }
        }
      }
    }

    return result;
  }
}
