package com.engine.nnue_trainer.v3;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.train.V3FeatureMiner;

/**
 * Runtime side of the NNUE v3 feature set: the {@code BOARD*BOARD} active feature ids of a {@code
 * (board, stm)} pair, one per cell.
 *
 * <p>Deliberately a thin delegation to {@link V3FeatureMiner#activeFeatures} — the training rows
 * the weights were fitted on came out of that method, so a second copy of {@code (r*12+c)*8+state}
 * here is exactly how the runtime and the training data would drift apart. Non-12x12 boards are
 * rejected there; the caller decides the fallback.
 *
 * <p>Full recompute per evaluation (144 reads); no incremental updates until a benchmark says the
 * leaf is the bottleneck.
 */
public final class NNUEv3Accumulator {

  /** v3 fixes the board size. */
  public static final int BOARD = V3FeatureMiner.BOARD;

  /** On-board cells are always one of PatternContract 0..7. */
  public static final int STATES = V3FeatureMiner.STATES;

  /** Total dense feature slots: 12*12*8 = 1152. */
  public static final int FEATURES = BOARD * BOARD * STATES;

  private NNUEv3Accumulator() {}

  /** Dense feature id for {@code (row, col, state)}. */
  public static int idx(int r, int c, int state) {
    return V3FeatureMiner.idx(r, c, state);
  }

  /**
   * The 144 active feature ids in row-major cell order, STM-normalized.
   *
   * @throws IllegalArgumentException if the board is not 12x12
   */
  public static int[] activeFeatures(Board board, int stm) {
    return V3FeatureMiner.activeFeatures(board, stm);
  }
}
