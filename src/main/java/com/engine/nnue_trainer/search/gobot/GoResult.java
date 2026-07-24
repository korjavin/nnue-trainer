package com.engine.nnue_trainer.search.gobot;

import com.engine.nnue_trainer.board.Action;
import java.util.List;

/**
 * Port of GoBot's {@code search.Result} ({@code search.go}). Carries the chosen action + score plus
 * output-only diagnostics. Named {@code GoResult} to avoid clashing with {@link
 * com.engine.nnue_trainer.search.SearchResult}.
 */
public final class GoResult {
  public Action action;
  public int score;
  public int depth;
  public long nodes;
  public long evaluations;
  public boolean budgetExhausted;
  public boolean searchComplete;

  /** Next-best root candidates (best-first), diagnostics only. */
  public List<RootMove> alternatives;

  public GoResult() {}

  public GoResult(Action action) {
    this.action = action;
  }
}
