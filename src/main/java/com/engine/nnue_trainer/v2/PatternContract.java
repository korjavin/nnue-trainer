package com.engine.nnue_trainer.v2;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import java.util.ArrayList;
import java.util.List;

public class PatternContract {

  public static final int SYMBOL_EMPTY = 0;
  public static final int SYMBOL_NEUTRAL = 1;
  public static final int SYMBOL_BASE_SELF = 2;
  public static final int SYMBOL_BASE_OPPONENT = 3;
  public static final int SYMBOL_NORMAL_SELF = 4;
  public static final int SYMBOL_NORMAL_OPPONENT = 5;
  public static final int SYMBOL_FORTIFIED_SELF = 6;
  public static final int SYMBOL_FORTIFIED_OPPONENT = 7;
  public static final int SYMBOL_OUT_OF_BOUNDS = 8;

  public static class WindowInfo {
    public final int centerRow;
    public final int centerCol;
    public final int[] pattern;
    public final int distanceBucket;

    public WindowInfo(int centerRow, int centerCol, int[] pattern, int distanceBucket) {
      this.centerRow = centerRow;
      this.centerCol = centerCol;
      this.pattern = pattern;
      this.distanceBucket = distanceBucket;
    }
  }

  public static int normalizeManhattan(
      int r, int c, int enemyBaseR, int enemyBaseC, int rows, int cols) {
    int dist = Math.abs(r - enemyBaseR) + Math.abs(c - enemyBaseC);
    int maxDist = (rows - 1) + (cols - 1);
    if (maxDist == 0) {
      return 0;
    }
    return Math.min(7, (dist * 7) / maxDist);
  }

  public static List<WindowInfo> extractWindows(
      Board board, int sideToMove, int enemyBaseR, int enemyBaseC) {
    List<WindowInfo> windows = new ArrayList<>();
    int rows = board.rows;
    int cols = board.cols;

    for (int r = 0; r < rows; r++) {
      for (int c = 0; c < cols; c++) {
        Cell centerCell = board.getCell(r, c);
        if (centerCell == null
            || centerCell.kind == CellKind.EMPTY
            || centerCell.kind == CellKind.NEUTRAL) {
          continue;
        }

        int[] pattern = new int[25];
        int idx = 0;

        for (int wr = r - 2; wr <= r + 2; wr++) {
          for (int wc = c - 2; wc <= c + 2; wc++) {
            if (!board.isValidPos(wr, wc)) {
              pattern[idx++] = SYMBOL_OUT_OF_BOUNDS;
            } else {
              Cell cell = board.getCell(wr, wc);
              if (cell == null || cell.kind == CellKind.EMPTY) {
                pattern[idx++] = SYMBOL_EMPTY;
              } else if (cell.kind == CellKind.NEUTRAL) {
                pattern[idx++] = SYMBOL_NEUTRAL;
              } else {
                boolean isSelf = (cell.owner == sideToMove);
                if (cell.kind == CellKind.BASE) {
                  pattern[idx++] = isSelf ? SYMBOL_BASE_SELF : SYMBOL_BASE_OPPONENT;
                } else if (cell.kind == CellKind.NORMAL) {
                  pattern[idx++] = isSelf ? SYMBOL_NORMAL_SELF : SYMBOL_NORMAL_OPPONENT;
                } else if (cell.kind == CellKind.FORTIFIED) {
                  pattern[idx++] = isSelf ? SYMBOL_FORTIFIED_SELF : SYMBOL_FORTIFIED_OPPONENT;
                } else {
                  pattern[idx++] = SYMBOL_EMPTY;
                }
              }
            }
          }
        }

        int distBucket = normalizeManhattan(r, c, enemyBaseR, enemyBaseC, rows, cols);
        windows.add(new WindowInfo(r, c, pattern, distBucket));
      }
    }

    return windows;
  }
}
