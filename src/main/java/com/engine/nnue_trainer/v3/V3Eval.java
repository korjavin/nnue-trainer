package com.engine.nnue_trainer.v3;

import com.engine.nnue_trainer.board.Board;
import java.io.IOException;
import java.nio.file.Path;

/**
 * A v3 leaf evaluator: 12x12 only, {@link NNUEv3Accumulator} features, output already in <b>hand-
 * tuned eval units</b> (no scale knob — see {@link NNUEv3Evaluator}).
 *
 * <p>Exists so the linear fit ({@link NNUEv3Evaluator}) and the hidden-layer net ({@link
 * NNUEv3NetEvaluator}) share one leaf path in the search: same units, same perspective convention,
 * same clamp. A second {@code LeafEval} constant and a parallel {@code nnueV3NetLeaf} would be two
 * copies of arithmetic that must never drift.
 */
public interface V3Eval {

  /** Leaf value from {@code stm}'s perspective, in hand-tuned eval units. */
  double evaluate(Board board, int stm);

  /**
   * Tempo-aware leaf value: {@code movesLeft} is the position's remaining actions this turn (0..3).
   * Evaluators without tempo features ignore it; a tempo net (1156-wide) requires this overload —
   * its 2-arg {@code evaluate} throws rather than silently assuming a tempo.
   */
  default double evaluate(Board board, int stm, int movesLeft) {
    return evaluate(board, stm);
  }

  /**
   * The evaluator the offline tools ({@code V3OrderingProbe}, {@code GauntletV3Run}) should use:
   * {@code V3EVAL=net} loads {@link NNUEv3NetEvaluator} from {@code NNUEV3NET_WEIGHTS}, anything
   * else the linear {@link NNUEv3Evaluator} from {@code NNUEV3_WEIGHTS}. Selecting the same feature
   * set from one place is what makes linear-vs-net a directly comparable measurement.
   */
  static V3Eval fromEnv() throws IOException {
    if ("net".equalsIgnoreCase(sysval("V3EVAL", "linear"))) {
      return NNUEv3NetEvaluator.load(
          Path.of(sysval("NNUEV3NET_WEIGHTS", NNUEv3NetEvaluator.DEFAULT_WEIGHTS.toString())));
    }
    return NNUEv3Evaluator.load(
        Path.of(sysval("NNUEV3_WEIGHTS", NNUEv3Evaluator.DEFAULT_WEIGHTS.toString())));
  }

  /** System property wins over env var, matching {@code SearchEngine.sysval}. */
  static String sysval(String key, String fallback) {
    String v = System.getProperty(key, System.getenv(key));
    return v != null ? v : fallback;
  }
}
