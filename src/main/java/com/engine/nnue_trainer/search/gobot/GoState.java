package com.engine.nnue_trainer.search.gobot;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;

/**
 * Faithful port of GoBot's {@code game.State} fields as seen by the search ({@code
 * ../virusgame/backend/game/state.go} + {@code search.go}'s {@code stateHash}).
 *
 * <p>This carries the non-board hidden state the search and its transposition-table key depend on:
 * {@code currentPlayer}, {@code movesLeft}, per-player {@code active}/{@code neutralUsed}, and the
 * grid. Move application / legal-action generation (GoBot's {@code Apply} / {@code Position}) land
 * in Task 3; Task 2 needs only the accessors and {@code hash} so the searcher scaffolding + TT can
 * be keyed exactly like GoBot.
 */
public final class GoState {

  final int rows;
  final int cols;
  final int players;
  final Cell[] cells; // row-major, length rows*cols
  final boolean[] active; // index player-1, length 4
  final boolean[] neutralUsed; // index player-1, length 4
  final int current;
  final int movesLeft;
  final int winner;
  final boolean over;

  GoState(
      int rows,
      int cols,
      int players,
      Cell[] cells,
      boolean[] active,
      boolean[] neutralUsed,
      int current,
      int movesLeft,
      int winner,
      boolean over) {
    this.rows = rows;
    this.cols = cols;
    this.players = players;
    this.cells = cells;
    this.active = active;
    this.neutralUsed = neutralUsed;
    this.current = current;
    this.movesLeft = movesLeft;
    this.winner = winner;
    this.over = over;
  }

  /**
   * Builds a mid-game position from a fixture record's {@code board + player + movesLeft +
   * neutralUsed} — the same hidden state the oracle emits. Our game is 1v1, so {@code players ==
   * 2}; {@code active[p]} is derived from an intact base (GoBot flips the flag only on elimination,
   * which for a non-terminal snapshot coincides with base-intact — same proxy as {@link
   * com.engine.nnue_trainer.search.eval.HandTunedEval}).
   */
  public static GoState fromBoard(
      Board board, int currentPlayer, int movesLeft, boolean[] neutralUsed) {
    int rows = board.rows;
    int cols = board.cols;
    Cell[] cells = new Cell[rows * cols];
    for (int r = 0; r < rows; r++) {
      for (int c = 0; c < cols; c++) {
        Cell cell = board.getCell(r, c);
        cells[r * cols + c] = new Cell(cell.owner, cell.kind);
      }
    }
    boolean[] active = new boolean[4];
    for (int p = 1; p <= 2; p++) {
      int bi = baseIndex(rows, cols, p);
      active[p - 1] = cells[bi].owner == p && cells[bi].kind == CellKind.BASE;
    }
    boolean[] nu = new boolean[4];
    if (neutralUsed != null) {
      System.arraycopy(neutralUsed, 0, nu, 0, Math.min(neutralUsed.length, 4));
    }
    return new GoState(rows, cols, 2, cells, active, nu, currentPlayer, movesLeft, 0, false);
  }

  /** Base cell corner for a player, matching HandTunedEval / GoBot's {@code bases}. */
  static int baseIndex(int rows, int cols, int player) {
    switch (player) {
      case 1:
        return 0; // (0,0)
      case 2:
        return (rows - 1) * cols + (cols - 1); // (rows-1, cols-1)
      case 3:
        return cols - 1; // (0, cols-1)
      default:
        return (rows - 1) * cols; // (rows-1, 0)
    }
  }

  public int rows() {
    return rows;
  }

  public int cols() {
    return cols;
  }

  public int currentPlayer() {
    return current;
  }

  public int movesLeft() {
    return movesLeft;
  }

  public boolean gameOver() {
    return over;
  }

  public int winner() {
    return winner;
  }

  public boolean active(int player) {
    return player >= 1 && player <= players && active[player - 1];
  }

  public boolean neutralUsed(int player) {
    return player >= 1 && player <= 4 && neutralUsed[player - 1];
  }

  public Cell at(int row, int col) {
    return cells[row * cols + col];
  }

  /**
   * FNV-1a position hash, byte-for-byte identical to GoBot's {@code stateHash} ({@code search.go}).
   * The TT is keyed by this, so it MUST match GoBot exactly (a divergence here silently breaks
   * parity). Java {@code long} multiplication wraps mod 2^64 like Go's {@code uint64}.
   */
  public long hash() {
    final long prime = 1099511628211L;
    long hash = 0xcbf29ce484222325L; // 1469598103934665603
    hash = add(hash, prime, rows);
    hash = add(hash, prime, cols);
    hash = add(hash, prime, current);
    hash = add(hash, prime, movesLeft);
    for (int player = 1; player <= 4; player++) {
      if (active(player)) {
        hash = add(hash, prime, player | 0x10);
      }
      if (neutralUsed(player)) {
        hash = add(hash, prime, player | 0x20);
      }
    }
    for (int row = 0; row < rows; row++) {
      for (int col = 0; col < cols; col++) {
        Cell cell = cells[row * cols + col];
        hash = add(hash, prime, (cell.owner << 3) | cell.kind.value);
      }
    }
    return hash;
  }

  private static long add(long hash, long prime, int value) {
    hash ^= (value & 0xffL);
    hash *= prime;
    return hash;
  }
}
