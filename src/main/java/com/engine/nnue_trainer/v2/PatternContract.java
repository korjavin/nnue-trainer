package com.engine.nnue_trainer.v2;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.board.Pos;
import java.util.ArrayList;
import java.util.List;

public class PatternContract {
  public static final int EMPTY = 0;
  public static final int NEUTRAL = 1;
  public static final int BASE_SELF = 2;
  public static final int BASE_OPPONENT = 3;
  public static final int NORMAL_SELF = 4;
  public static final int NORMAL_OPPONENT = 5;
  public static final int FORTIFIED_SELF = 6;
  public static final int FORTIFIED_OPPONENT = 7;
  public static final int OUT_OF_BOUNDS = 8;

  public static class Window {
    public final int centerRow;
    public final int centerCol;
    public final int[] symbols;
    public final int distanceBucket;

    public Window(int centerRow, int centerCol, int[] symbols, int distanceBucket) {
      this.centerRow = centerRow;
      this.centerCol = centerCol;
      this.symbols = symbols;
      this.distanceBucket = distanceBucket;
    }
  }

  public static int getSymbol(Cell cell, int stmOwner) {
    if (cell == null) {
      return OUT_OF_BOUNDS;
    }
    if (cell.kind == CellKind.EMPTY) {
      return EMPTY;
    }
    if (cell.kind == CellKind.NEUTRAL) {
      return NEUTRAL;
    }

    boolean isSelf = (cell.owner == stmOwner);

    switch (cell.kind) {
      case BASE:
        return isSelf ? BASE_SELF : BASE_OPPONENT;
      case NORMAL:
        return isSelf ? NORMAL_SELF : NORMAL_OPPONENT;
      case FORTIFIED:
        return isSelf ? FORTIFIED_SELF : FORTIFIED_OPPONENT;
      default:
        return EMPTY;
    }
  }

  public static int getDistanceBucket(int r, int c, Pos enemyBase) {
    if (enemyBase == null) return 7;
    int dist = Math.abs(r - enemyBase.row) + Math.abs(c - enemyBase.col);
    return Math.min(dist, 7);
  }

  public static Pos findEnemyBase(Board board, int stmOwner) {
    for (int r = 0; r < board.rows; r++) {
      for (int c = 0; c < board.cols; c++) {
        Cell cell = board.getCell(r, c);
        if (cell != null && cell.kind == CellKind.BASE && cell.owner != stmOwner) {
          return new Pos(r, c);
        }
      }
    }
    return null;
  }

  public static List<Window> extractWindows(Board board, int stmOwner) {
    List<Window> windows = new ArrayList<>();
    Pos enemyBase = findEnemyBase(board, stmOwner);

    for (int r = 0; r < board.rows; r++) {
      for (int c = 0; c < board.cols; c++) {
        int[] symbols = new int[25];
        boolean allEmptyOrOob = true;
        int idx = 0;

        for (int wr = r - 2; wr <= r + 2; wr++) {
          for (int wc = c - 2; wc <= c + 2; wc++) {
            int sym;
            if (!board.isValidPos(wr, wc)) {
              sym = OUT_OF_BOUNDS;
            } else {
              Cell cell = board.getCell(wr, wc);
              sym = getSymbol(cell, stmOwner);
            }
            symbols[idx++] = sym;
            if (sym != EMPTY && sym != OUT_OF_BOUNDS) {
              allEmptyOrOob = false;
            }
          }
        }

        if (!allEmptyOrOob) {
          windows.add(new Window(r, c, symbols, getDistanceBucket(r, c, enemyBase)));
        }
      }
    }

    return windows;
  }
}
