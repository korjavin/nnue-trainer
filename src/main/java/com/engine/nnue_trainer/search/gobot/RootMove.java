package com.engine.nnue_trainer.search.gobot;

import com.engine.nnue_trainer.board.Action;

/**
 * A root candidate action with its search score — port of GoBot's {@code RootMove} ({@code
 * search.go}). Never affects the chosen action.
 *
 * <p>{@code exact} is false when the score is a scout fail-low bound rather than a value: a
 * null-window child that does not beat the running alpha keeps a score pinned to that alpha, which
 * rises with sibling order. Ranking on those numbers ranks move ORDER, not move quality, so
 * anything that samples over the scores ({@link GoBotExploration}) must skip them.
 */
public final class RootMove {
  public final Action action;
  public final int score;
  public final boolean exact;

  public RootMove(Action action, int score) {
    this(action, score, true);
  }

  public RootMove(Action action, int score, boolean exact) {
    this.action = action;
    this.score = score;
    this.exact = exact;
  }
}
