package com.engine.nnue_trainer.search.gobot;

import java.util.HashMap;
import java.util.Map;

/**
 * Port of GoBot's {@code searcher} ({@code ../virusgame/backend/search/search.go}) — the mutable
 * per-search state: the transposition table, node/evaluation counters, the running-budget guard,
 * and the search root. Task 2 covers the scaffolding + TT probe/store; {@code minimax}/{@code
 * maxN}/{@code ChooseDepth} land in Task 3.
 *
 * <p>Faithful translation, not an improvement — keep the structure so move choices match GoBot.
 */
public final class GoBotSearcher {

  // TT bound flags for fail-soft alpha-beta stores (GoBot's flagExact/flagLower/flagUpper iota).
  public static final int FLAG_EXACT = 0;
  public static final int FLAG_LOWER = 1;
  public static final int FLAG_UPPER = 2;

  static final int MAX_DEPTH = 64;
  static final int INF_SCORE = 1 << 60;

  final int root;
  final boolean multi;
  final Map<Long, TableEntry> table;
  long nodes;
  long evaluations;
  long nodeLimit; // 0 == unlimited
  long deadlineMillis; // 0 == no wall-clock deadline

  GoBotSearcher(int root, boolean multi) {
    this.root = root;
    this.multi = multi;
    this.table = new HashMap<>();
  }

  /** Port of GoBot's {@code newSearcher}: {@code multi} iff more than two players are active. */
  public static GoBotSearcher newSearcher(GoState state) {
    int activeCount = 0;
    for (int player = 1; player <= 4; player++) {
      if (state.active(player)) {
        activeCount++;
      }
    }
    return new GoBotSearcher(state.currentPlayer(), activeCount > 2);
  }

  /** TT probe: the stored entry for this position hash, or {@code null} on a miss. */
  public TableEntry probe(long key) {
    return table.get(key);
  }

  /** TT store: GoBot overwrites unconditionally ({@code s.table[key] = ...}). */
  public void store(long key, TableEntry entry) {
    table.put(key, entry);
  }

  /**
   * Port of GoBot's {@code running()}: stop when a node budget is exhausted or a wall-clock
   * deadline has passed. A fixed-depth {@code ChooseDepth} has neither, so it always runs to
   * completion.
   */
  public boolean running() {
    if (nodeLimit > 0 && nodes >= nodeLimit) {
      return false;
    }
    return deadlineMillis <= 0 || System.currentTimeMillis() < deadlineMillis;
  }
}
