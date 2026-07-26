package com.engine.nnue_trainer.v3;

import com.engine.nnue_trainer.board.Board;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Opt-in NNUE v3 <b>net</b> leaf evaluator — the nonlinear sibling of {@link NNUEv3Evaluator}:
 * {@code eval = b2 + sum_h w2[h] * relu(b1[h] + sum_{i in active} w1[h][i])} over the 144 active
 * features (see {@link NNUEv3Accumulator}).
 *
 * <p>Why a hidden layer at all: the linear fit scored held-out R² = 0.976 yet lost the strength
 * gauntlet 7-17, because search orders SIBLING moves and the linear model's MAE (~1230) is ~0.9x
 * the median sibling gap (~1299). A global fit that good and an ordering that bad means the missing
 * signal is interaction between features, which a linear model cannot represent at any R².
 *
 * <p>The output is already in <b>hand-tuned eval units</b> (the net is fitted against {@code
 * HandTunedEval.staticEval} labels), so there is deliberately no score scale here — same reasoning
 * as the linear evaluator, and unlike v2's {@code NNUEV2_SCALE}.
 *
 * <p>Evaluation is sparse: exactly 144 of 1152 input slots are active, so the hidden accumulator is
 * built by adding 144 <i>columns</i> of {@code w1} rather than by a dense 1152xH matmul. {@link
 * #w1} is therefore stored column-major ({@code w1[feature * hidden + h]}) so each active feature's
 * H weights are contiguous.
 *
 * <p>Everything is validated at load: a bad weights file must fail here, once, and not as an AIOOBE
 * or a silent NaN somewhere deep in a search.
 */
public final class NNUEv3NetEvaluator implements V3Eval {

  public static final Path DEFAULT_WEIGHTS = Path.of("nnue_v3_net.json");

  private final int hidden;
  private final double[] w1; // [FEATURES * hidden], column-major: w1[i * hidden + h]
  private final double[] b1; // [hidden]
  private final double[] w2; // [hidden]
  private final double b2;

  /** {@code w1} is row-major {@code [hidden][FEATURES]}, as the weights file stores it. */
  public NNUEv3NetEvaluator(double[][] w1, double[] b1, double[] w2, double b2) {
    this.hidden = b1.length;
    if (w2.length != hidden || w1.length != hidden) {
      throw new IllegalArgumentException(
          "v3 net: hidden size mismatch — w1 rows "
              + w1.length
              + ", b1 "
              + b1.length
              + ", w2 "
              + w2.length);
    }
    this.w1 = new double[NNUEv3Accumulator.FEATURES * hidden];
    for (int h = 0; h < hidden; h++) {
      if (w1[h].length != NNUEv3Accumulator.FEATURES) {
        throw new IllegalArgumentException(
            "v3 net: w1 row "
                + h
                + " has "
                + w1[h].length
                + " entries, expected "
                + NNUEv3Accumulator.FEATURES);
      }
      for (int i = 0; i < NNUEv3Accumulator.FEATURES; i++) {
        this.w1[i * hidden + h] = w1[h][i];
      }
    }
    this.b1 = b1.clone();
    this.w2 = w2.clone();
    this.b2 = b2;
  }

  public int hidden() {
    return hidden;
  }

  /** Leaf value from {@code stm}'s perspective, in hand-tuned eval units. */
  @Override
  public double evaluate(Board board, int stm) {
    double[] acc = b1.clone();
    for (int f : NNUEv3Accumulator.activeFeatures(board, stm)) {
      int base = f * hidden;
      for (int h = 0; h < hidden; h++) {
        acc[h] += w1[base + h];
      }
    }
    double out = b2;
    for (int h = 0; h < hidden; h++) {
      if (acc[h] > 0) { // relu
        out += w2[h] * acc[h];
      }
    }
    return out;
  }

  /**
   * Loads {@code {"meta": {"hidden": H, "features": 1152, ..}, "w1": [[..]], "b1": [..], "w2":
   * [..], "b2": f}} as written by the v3 net trainer.
   */
  public static NNUEv3NetEvaluator load(Path weightsJson) throws IOException {
    JsonNode root = new ObjectMapper().readTree(Files.readAllBytes(weightsJson));
    JsonNode meta = root.path("meta");
    if (!meta.isObject()) {
      throw new IOException("v3 net: missing/non-object \"meta\" in " + weightsJson);
    }
    // A file fitted over a different feature space would load "fine" and evaluate nonsense.
    int features = meta.path("features").asInt(-1);
    if (features != NNUEv3Accumulator.FEATURES) {
      throw new IOException(
          "v3 net: meta.features "
              + features
              + " != "
              + NNUEv3Accumulator.FEATURES
              + " in "
              + weightsJson);
    }
    int hidden = meta.path("hidden").asInt(-1);
    if (hidden <= 0) {
      throw new IOException("v3 net: meta.hidden must be a positive int, got " + hidden);
    }

    double[] b1 = vector(root.path("b1"), hidden, "b1", weightsJson);
    double[] w2 = vector(root.path("w2"), hidden, "w2", weightsJson);
    JsonNode w1Node = root.path("w1");
    if (!w1Node.isArray() || w1Node.size() != hidden) {
      throw new IOException(
          "v3 net: \"w1\" must be an array of "
              + hidden
              + " rows, got "
              + (w1Node.isArray() ? w1Node.size() + " rows" : w1Node.getNodeType())
              + " in "
              + weightsJson);
    }
    double[][] w1 = new double[hidden][];
    for (int h = 0; h < hidden; h++) {
      w1[h] = vector(w1Node.get(h), features, "w1[" + h + "]", weightsJson);
    }

    JsonNode b2 = root.path("b2");
    if (!b2.isNumber() || !Double.isFinite(b2.doubleValue())) {
      throw new IOException("v3 net: \"b2\" must be a finite number, got " + b2);
    }
    return new NNUEv3NetEvaluator(w1, b1, w2, b2.doubleValue());
  }

  /** A JSON array of exactly {@code n} finite numbers, or an {@link IOException} naming it. */
  private static double[] vector(JsonNode node, int n, String what, Path src) throws IOException {
    if (node == null || !node.isArray() || node.size() != n) {
      throw new IOException(
          "v3 net: \""
              + what
              + "\" must be an array of "
              + n
              + " numbers, got "
              + (node != null && node.isArray() ? node.size() + " entries" : String.valueOf(node))
              + " in "
              + src);
    }
    double[] out = new double[n];
    for (int i = 0; i < n; i++) {
      JsonNode v = node.get(i);
      if (!v.isNumber() || !Double.isFinite(v.doubleValue())) {
        throw new IOException("v3 net: " + what + "[" + i + "] must be finite, got " + v);
      }
      out[i] = v.doubleValue();
    }
    return out;
  }
}
