package com.engine.nnue_trainer.search.gobot;

import com.engine.nnue_trainer.board.Action;

/**
 * A root candidate action with its search score — port of GoBot's {@code RootMove} ({@code
 * search.go}). Non-chosen scores come from scout searches and may be bounds; this is diagnostics
 * metadata only and never affects the chosen action.
 */
public final class RootMove {
  public final Action action;
  public final int score;

  public RootMove(Action action, int score) {
    this.action = action;
    this.score = score;
  }
}
