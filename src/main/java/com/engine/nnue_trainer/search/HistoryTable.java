package com.engine.nnue_trainer.search;

import com.engine.nnue_trainer.board.Action;
import com.engine.nnue_trainer.board.MoveAction;
import com.engine.nnue_trainer.board.Pos;

public class HistoryTable {
  // Simple history table indexed by player (1 or 2), row, col
  // Max size is 3 x 12 x 12
  private final int[][][] table = new int[3][12][12];

  public void addBonus(int player, Action action, int depth) {
    if (action instanceof MoveAction) {
      Pos target = ((MoveAction) action).target;
      // Bonus scales with depth
      table[player][target.row][target.col] += (depth * depth);
    }
  }

  public int getScore(int player, Action action) {
    if (action instanceof MoveAction) {
      Pos target = ((MoveAction) action).target;
      return table[player][target.row][target.col];
    }
    return 0;
  }

  public void clear() {
    for (int p = 0; p < 3; p++) {
      for (int r = 0; r < 12; r++) {
        for (int c = 0; c < 12; c++) {
          table[p][r][c] = 0;
        }
      }
    }
  }
}
