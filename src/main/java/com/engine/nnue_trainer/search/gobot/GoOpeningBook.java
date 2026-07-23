package com.engine.nnue_trainer.search.gobot;

import com.engine.nnue_trainer.board.Action;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.board.MoveAction;
import com.engine.nnue_trainer.board.Pos;
import com.engine.nnue_trainer.search.eval.HandTunedEval;

/**
 * Faithful port of GoBot's {@code opening_book.go} ({@code ../virusgame/backend/search/}).
 *
 * <p>The canonical "wedge" first-turn placement: one base-adjacent diagonal anchor, then a width-2
 * advancing pair, oriented inward toward the board center. It fires only while the current player
 * owns exactly its base plus a prefix of that block (its genuine first turn, spread over the three
 * per-move {@code Choose} calls); any own non-base cell outside the block, or a block cell that is
 * not reachable, voids the book and search runs unchanged.
 *
 * <p>Only the live entry points ({@link GoBotSearcher#choose}, {@link
 * GoBotSearcher#chooseNodeBudget}) consult the book — {@link GoBotSearcher#chooseDepth} (the parity
 * oracle) deliberately skips it, so the checked-in fixtures are pure search.
 */
final class GoOpeningBook {

  private GoOpeningBook() {}

  /**
   * Port of {@code openingBookResult}: wraps {@link #openingBookMove} as a completed depth-0 {@link
   * GoResult} (so live entry points short-circuit their iterative deepening on the opening turn),
   * or {@code null} to defer to search. The score is a real static eval of the resulting position,
   * for a meaningful diagnostics number; depth/nodes stay 0 so the move can be labelled "book".
   */
  static GoResult openingBookResult(GoState state) {
    Action action = openingBookMove(state);
    if (action == null) {
      return null;
    }
    int player = state.currentPlayer();
    int score = 0;
    GoState next = state.apply(action);
    if (next != null) {
      score =
          HandTunedEval.staticEval(
              next.toBoard(), player, next.currentPlayer(), next.movesLeft(), next.neutralUsed);
    }
    GoResult result = new GoResult(action);
    result.score = score;
    result.searchComplete = true;
    return result;
  }

  /**
   * Port of {@code openingBookMove}: the canonical thick first-turn placement for the current
   * player, or {@code null} to defer to search.
   */
  static Action openingBookMove(GoState state) {
    if (state.gameOver()) {
      return null;
    }
    int player = state.currentPlayer();
    if (!state.active(player)) {
      return null;
    }
    Pos base = findBase(state, player);
    if (base == null) {
      return null;
    }

    // Orient inward toward the board center. Starting bases are corners, so each delta resolves to
    // +1 or -1; the comparison keeps it correct for odd sizes.
    int dr = base.row * 2 < state.rows() - 1 ? 1 : -1;
    int dc = base.col * 2 < state.cols() - 1 ? 1 : -1;

    // The "wedge": one base-adjacent cell, then a width-2 advancing pair. Order is load-bearing:
    // later cells connect via earlier ones, so they must be placed in array order.
    Pos[] block = {
      new Pos(base.row + dr, base.col + dc), // base-adjacent diagonal anchor
      new Pos(base.row + 2 * dr, base.col + dc), // advancing pair, straight
      new Pos(base.row + 2 * dr, base.col + 2 * dc), // advancing pair, diagonal
    };

    // Any own non-base cell outside the block means this is not a fresh opening turn (mid-game or a
    // seeded position) — defer to search.
    for (int row = 0; row < state.rows(); row++) {
      for (int col = 0; col < state.cols(); col++) {
        Cell cell = state.at(row, col);
        Pos pos = new Pos(row, col);
        if (cell.owner == player && cell.kind != CellKind.BASE && !inBlock(block, pos)) {
          return null;
        }
      }
    }

    // Every wedge cell must be reachable: already ours from an earlier book move, or a legal empty
    // placement. A collision — out of bounds, or another player's cell/base on a tiny board — voids
    // the book. The first still-empty cell in array order is the next move.
    Pos next = null;
    int placed = 0;
    for (Pos b : block) {
      if (!state.inBounds(b)) {
        return null; // GoBot's state.At returns ok=false out of bounds
      }
      Cell cell = state.at(b.row, b.col);
      if (cell.kind == CellKind.EMPTY) {
        if (next == null) {
          next = b;
        }
      } else if (cell.owner == player) {
        placed++; // placed by an earlier book move this turn
      } else {
        return null;
      }
    }
    if (next == null) {
      return null; // block already complete — opening over
    }
    // Only fire on the player's genuine first turn. Across that turn's three per-move Choose calls
    // placed+movesLeft is invariant at 3 (0+3, 1+2, 2+1); any later turn defers to search.
    if (placed + state.movesLeft() != 3) {
      return null;
    }
    return new MoveAction(next);
  }

  private static boolean inBlock(Pos[] block, Pos p) {
    return p.equals(block[0]) || p.equals(block[1]) || p.equals(block[2]);
  }

  /** Port of {@code findBase}: the current player's Base cell, or {@code null}. */
  private static Pos findBase(GoState state, int player) {
    for (int row = 0; row < state.rows(); row++) {
      for (int col = 0; col < state.cols(); col++) {
        Cell cell = state.at(row, col);
        if (cell.owner == player && cell.kind == CellKind.BASE) {
          return new Pos(row, col);
        }
      }
    }
    return null;
  }
}
