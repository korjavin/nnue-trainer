package com.engine.nnue_trainer.search;

import com.engine.nnue_trainer.board.Action;

public class SearchResult {
  public final Action bestAction;
  public final float score;
  public final int depth;
  public final int nodesEvaluated;
  public final long timeMs;

  public SearchResult(Action bestAction, float score, int depth, int nodesEvaluated, long timeMs) {
    this.bestAction = bestAction;
    this.score = score;
    this.depth = depth;
    this.nodesEvaluated = nodesEvaluated;
    this.timeMs = timeMs;
  }
}
