package com.engine.nnue_trainer.search;

import com.engine.nnue_trainer.board.Action;

public class KillerMoves {
  private static final int MAX_DEPTH = 64; // Max depth we expect to search
  private final Action[][] table = new Action[MAX_DEPTH][2]; // 2 slots per depth

  public void addKiller(int depth, Action action) {
    if (depth < 0 || depth >= MAX_DEPTH) return;

    // Don't add duplicate
    if (action.equals(table[depth][0])) return;

    // Shift killer 1 to killer 2
    table[depth][1] = table[depth][0];
    table[depth][0] = action;
  }

  public boolean isKiller(int depth, Action action) {
    if (depth < 0 || depth >= MAX_DEPTH) return false;
    return action.equals(table[depth][0]) || action.equals(table[depth][1]);
  }

  public void clear() {
    for (int i = 0; i < MAX_DEPTH; i++) {
      table[i][0] = null;
      table[i][1] = null;
    }
  }
}
