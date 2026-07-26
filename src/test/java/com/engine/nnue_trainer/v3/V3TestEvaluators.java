package com.engine.nnue_trainer.v3;

import com.engine.nnue_trainer.v2.PatternContract;

/**
 * Stub {@link NNUEv3Evaluator}s for the leaf/env-hook tests. {@code NNUEv3Evaluator} is final by
 * design, so these build real weight vectors instead of subclassing.
 */
public final class V3TestEvaluators {

  private V3TestEvaluators() {}

  /** Every weight 0, bias {@code value} → evaluates to exactly {@code value} on any 12x12 board. */
  public static NNUEv3Evaluator constant(double value) {
    return new NNUEv3Evaluator(new double[NNUEv3Accumulator.FEATURES], value);
  }

  /**
   * {@code evaluate(board, stm) == (stm's NORMAL stones) - (opponent's NORMAL stones)}: +1 on every
   * NORMAL_SELF slot, -1 on every NORMAL_OPPONENT slot. Zero-sum between the two perspectives, so
   * it exercises the leaf's negate-to-root step.
   */
  public static NNUEv3Evaluator stoneCount() {
    double[] w = new double[NNUEv3Accumulator.FEATURES];
    for (int r = 0; r < NNUEv3Accumulator.BOARD; r++) {
      for (int c = 0; c < NNUEv3Accumulator.BOARD; c++) {
        w[NNUEv3Accumulator.idx(r, c, PatternContract.NORMAL_SELF)] = 1.0;
        w[NNUEv3Accumulator.idx(r, c, PatternContract.NORMAL_OPPONENT)] = -1.0;
      }
    }
    return new NNUEv3Evaluator(w, 0.0);
  }

  /**
   * {@code evaluate(board, stm) == (stm's NORMAL stones)}: +1 on every NORMAL_SELF slot only.
   * Deliberately NOT antisymmetric, so it tells apart "query the mover and negate" from "query the
   * perspective directly" — the two agree on any zero-sum stub.
   */
  public static NNUEv3Evaluator selfStones() {
    double[] w = new double[NNUEv3Accumulator.FEATURES];
    for (int r = 0; r < NNUEv3Accumulator.BOARD; r++) {
      for (int c = 0; c < NNUEv3Accumulator.BOARD; c++) {
        w[NNUEv3Accumulator.idx(r, c, PatternContract.NORMAL_SELF)] = 1.0;
      }
    }
    return new NNUEv3Evaluator(w, 0.0);
  }
}
