package com.engine.nnue_trainer.search.gobot;

import com.engine.nnue_trainer.board.Action;

/**
 * Port of GoBot's {@code tableEntry} ({@code search.go}). Immutable transposition-table record: the
 * fail-soft bound {@code flag} (see {@link GoBotSearcher#FLAG_EXACT}/{@code FLAG_LOWER}/{@code
 * FLAG_UPPER}) applies at exactly this {@code depth} and {@code ply}. {@code values} holds the
 * per-player scores ({@code maxN}); minimax uses index 0 only.
 */
public final class TableEntry {
  public final int depth;
  public final int ply;
  public final int flag;
  public final Action bestAction;
  public final int[] values; // length 4

  public TableEntry(int depth, int ply, int flag, Action bestAction, int[] values) {
    this.depth = depth;
    this.ply = ply;
    this.flag = flag;
    this.bestAction = bestAction;
    this.values = values;
  }

  /** Convenience for the minimax path, which stores a single score in slot 0. */
  public static TableEntry single(int depth, int ply, int flag, Action bestAction, int value) {
    return new TableEntry(depth, ply, flag, bestAction, new int[] {value, 0, 0, 0});
  }
}
