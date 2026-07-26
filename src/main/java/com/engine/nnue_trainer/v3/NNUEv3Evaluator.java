package com.engine.nnue_trainer.v3;

import com.engine.nnue_trainer.board.Board;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;

/**
 * Opt-in NNUE v3 leaf evaluator: {@code eval(board, stm) = bias + sum of weight[f] over the 144
 * active features} (see {@link NNUEv3Accumulator}).
 *
 * <p>The output is already in <b>hand-tuned eval units</b> — the ridge fit regressed directly on
 * {@code HandTunedEval.staticEval} labels (holdout MAE ~1230 on a +-30000 range). There is
 * deliberately no score scale here: the v2 {@code NNUEV2_SCALE} knob existed because that net
 * emitted a WDL-ish scalar. If a scale ever looks necessary, the fit is wrong.
 *
 * <p>Everything is validated at load: a bad weights file must fail here, once, and not as an AIOOBE
 * or a silent NaN somewhere deep in a search.
 */
public final class NNUEv3Evaluator implements V3Eval {

  public static final Path DEFAULT_WEIGHTS = Path.of("nnue_v3_weights.json");

  private final double[] weights; // [FEATURES], missing entries stay 0
  private final double bias;

  public NNUEv3Evaluator(double[] weights, double bias) {
    if (weights.length != NNUEv3Accumulator.FEATURES) {
      throw new IllegalArgumentException(
          "expected " + NNUEv3Accumulator.FEATURES + " weights, got " + weights.length);
    }
    this.weights = weights;
    this.bias = bias;
  }

  /** Leaf value from {@code stm}'s perspective, in hand-tuned eval units. */
  @Override
  public double evaluate(Board board, int stm) {
    double sum = bias;
    for (int f : NNUEv3Accumulator.activeFeatures(board, stm)) {
      sum += weights[f];
    }
    return sum;
  }

  /**
   * Loads {@code {"meta": {"bias": .., "n_features_total": 1152}, "weights": {"<id>": w, ..}}} as
   * written by {@code python/v3/fit_capacity.py}. Absent feature ids mean weight 0.
   */
  public static NNUEv3Evaluator load(Path weightsJson) throws IOException {
    JsonNode root = new ObjectMapper().readTree(Files.readAllBytes(weightsJson));
    JsonNode meta = root.path("meta");
    JsonNode w = root.path("weights");
    if (!meta.isObject()) {
      throw new IOException("v3 weights: missing/non-object \"meta\" in " + weightsJson);
    }
    if (!w.isObject()) {
      throw new IOException("v3 weights: missing/non-object \"weights\" in " + weightsJson);
    }
    JsonNode biasNode = meta.path("bias");
    if (!biasNode.isNumber() || !Double.isFinite(biasNode.doubleValue())) {
      throw new IOException("v3 weights: \"meta.bias\" must be a finite number, got " + biasNode);
    }
    // A file fitted over a different feature space would load "fine" and evaluate nonsense.
    int total = meta.path("n_features_total").asInt(-1);
    if (total != NNUEv3Accumulator.FEATURES) {
      throw new IOException(
          "v3 weights: n_features_total "
              + total
              + " != "
              + NNUEv3Accumulator.FEATURES
              + " in "
              + weightsJson);
    }

    double[] weights = new double[NNUEv3Accumulator.FEATURES];
    for (Iterator<Map.Entry<String, JsonNode>> it = w.fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> e = it.next();
      int f;
      try {
        f = Integer.parseInt(e.getKey());
      } catch (NumberFormatException ex) {
        throw new IOException("v3 weights: non-integer feature id \"" + e.getKey() + "\"");
      }
      if (f < 0 || f >= total) {
        throw new IOException("v3 weights: feature id " + f + " outside [0," + total + ")");
      }
      JsonNode v = e.getValue();
      if (!v.isNumber() || !Double.isFinite(v.doubleValue())) {
        throw new IOException("v3 weights: feature " + f + " weight must be finite, got " + v);
      }
      weights[f] = v.doubleValue();
    }
    return new NNUEv3Evaluator(weights, biasNode.doubleValue());
  }
}
