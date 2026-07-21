package com.engine.nnue_trainer.search;

import com.engine.nnue_trainer.board.Action;

public class TTEntry {
  public static final byte FLAG_EXACT = 0;
  public static final byte FLAG_LOWER_BOUND = 1; // Beta cutoff (fail-high)
  public static final byte FLAG_UPPER_BOUND = 2; // Alpha cutoff (fail-low)

  public long zobristKey;
  public Action bestAction;
  public float score;
  public int depth;
  public byte flag;

  public TTEntry() {
    this.zobristKey = 0;
  }
}
