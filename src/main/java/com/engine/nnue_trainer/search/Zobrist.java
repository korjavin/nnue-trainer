package com.engine.nnue_trainer.search;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import java.util.Random;

public class Zobrist {
  public static final int MAX_ROWS = 12;
  public static final int MAX_COLS = 12;
  public static final int NUM_OWNERS = 3; // 0, 1, 2
  public static final int NUM_KINDS = 5; // EMPTY, NORMAL, BASE, FORTIFIED, NEUTRAL

  public static final long[][][][] PIECE_KEYS = new long[MAX_ROWS][MAX_COLS][NUM_OWNERS][NUM_KINDS];
  public static final long PLAYER_KEY;

  static {
    // We use a fixed seed to ensure Zobrist keys are consistent across runs for debugging/testing
    Random rnd = new Random(1337);
    for (int r = 0; r < MAX_ROWS; r++) {
      for (int c = 0; c < MAX_COLS; c++) {
        for (int o = 0; o < NUM_OWNERS; o++) {
          for (int k = 0; k < NUM_KINDS; k++) {
            PIECE_KEYS[r][c][o][k] = rnd.nextLong();
          }
        }
      }
    }
    PLAYER_KEY = rnd.nextLong();
  }

  public static long computeHash(Board board, int player) {
    long hash = 0;
    for (int r = 0; r < board.rows; r++) {
      for (int c = 0; c < board.cols; c++) {
        Cell cell = board.getCell(r, c);
        if (cell != null) {
          int owner = cell.owner;
          int kind = cell.kind.value;
          if (r < MAX_ROWS
              && c < MAX_COLS
              && owner >= 0
              && owner < NUM_OWNERS
              && kind >= 0
              && kind < NUM_KINDS) {
            hash ^= PIECE_KEYS[r][c][owner][kind];
          }
        }
      }
    }
    if (player == 1) {
      hash ^= PLAYER_KEY;
    }
    return hash;
  }
}
