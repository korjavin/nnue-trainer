package com.engine.nnue_trainer.mcts;

import com.engine.nnue_trainer.board.Action;
import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.MoveAction;
import com.engine.nnue_trainer.board.PlaceNeutralsAction;
import com.engine.nnue_trainer.board.Pos;
import com.engine.nnue_trainer.search.gobot.GoState;
import com.engine.nnue_trainer.v2.PatternContract;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Trained policy prior (plan 20260807-mcts-az-feasibility D2): the conv policy net exported by
 * {@code python/mcts/train_policy.py}, hand-rolled float-array inference like {@code
 * NNUEv3NetEvaluator} — no ONNX, no new dependency.
 *
 * <p>Input is 13 planes 12x12 built from the node's <b>own</b> mover ({@link
 * PatternContract#getSymbol} 8-state one-hot + movesLeft one-hot + own/opp neutralUsed), exactly
 * the training-time encoding — the emitter, trainer, and this loader share it by construction, and
 * {@code PolicyNetPriorParityTest} pins it against a python-computed fixture.
 *
 * <p>Heads: 144 move logits (one per {@code MoveAction} target) and 144 per-cell utilities {@code
 * u} with {@code logit(PlaceNeutrals{i,j}) = u[i] + u[j] + b_pair} (factored pair head). The prior
 * is the softmax over the node's legal actions only.
 *
 * <p>Phase 2 artifacts additionally carry a {@code value_head} section (GAP over trunk channels →
 * dense → 1, tanh; exported by {@code python/mcts/train_selfplay.py}) — a <b>mover-frame</b> value
 * in [-1, 1] served via {@link #valueMover}. Artifacts without it still load ({@link #hasValueHead}
 * is false) and the searcher falls back to the hand-tuned leaf.
 */
public final class PolicyNetPrior implements PolicyPrior {

  private static final int BOARD = 12;
  private static final int CELLS = BOARD * BOARD;
  private static final int PLANES = 13;

  private final int channels;
  private final int layers;
  // conv[k]: weights [out][in][3][3] flattened to out*(in*9), biases [out].
  private final double[][] convW;
  private final double[][] convB;
  private final double[] moveW; // [channels]
  private final double moveB;
  private final double[] pairW; // [channels]
  private final double pairB;
  private final double pairBias;
  // Optional value head (null when the artifact has none): GAP -> fc1 (relu) -> fc2 -> tanh.
  private final double[] valueFc1W; // [hidden * channels]
  private final double[] valueFc1B; // [hidden]
  private final double[] valueFc2W; // [hidden]
  private final double valueFc2B;

  private PolicyNetPrior(
      int channels,
      int layers,
      double[][] convW,
      double[][] convB,
      double[] moveW,
      double moveB,
      double[] pairW,
      double pairB,
      double pairBias,
      double[] valueFc1W,
      double[] valueFc1B,
      double[] valueFc2W,
      double valueFc2B) {
    this.channels = channels;
    this.layers = layers;
    this.convW = convW;
    this.convB = convB;
    this.moveW = moveW;
    this.moveB = moveB;
    this.pairW = pairW;
    this.pairB = pairB;
    this.pairBias = pairBias;
    this.valueFc1W = valueFc1W;
    this.valueFc1B = valueFc1B;
    this.valueFc2W = valueFc2W;
    this.valueFc2B = valueFc2B;
  }

  public boolean hasValueHead() {
    return valueFc1W != null;
  }

  /**
   * Trained value for the position in its own mover's frame (tanh, [-1, 1]) — the exact frame the
   * trainer's targets are in ({@code z_mover = z_abs} flipped by mover, the v3 lesson). Callers
   * convert to the absolute frame with the mover sign, same as {@code MctsSearcher.leafValueAbs}.
   */
  public double valueMover(GoState state) {
    Board board = state.toBoard();
    int mover = state.currentPlayer();
    int[] sym = new int[CELLS];
    for (int r = 0; r < BOARD; r++) {
      for (int c = 0; c < BOARD; c++) {
        sym[r * BOARD + c] = PatternContract.getSymbol(board.getCell(r, c), mover);
      }
    }
    return forward(
            sym,
            state.movesLeft(),
            state.neutralUsed(mover) ? 1 : 0,
            state.neutralUsed(3 - mover) ? 1 : 0)
        .value;
  }

  @Override
  public float[] priors(GoState state, List<Action> actions) {
    Board board = state.toBoard();
    int mover = state.currentPlayer();
    int[] sym = new int[CELLS];
    for (int r = 0; r < BOARD; r++) {
      for (int c = 0; c < BOARD; c++) {
        sym[r * BOARD + c] = PatternContract.getSymbol(board.getCell(r, c), mover);
      }
    }
    Heads heads =
        forward(
            sym,
            state.movesLeft(),
            state.neutralUsed(mover) ? 1 : 0,
            state.neutralUsed(3 - mover) ? 1 : 0);

    double[] logits = new double[actions.size()];
    double max = Double.NEGATIVE_INFINITY;
    for (int a = 0; a < actions.size(); a++) {
      Action action = actions.get(a);
      if (action instanceof MoveAction) {
        logits[a] = heads.move[cell(((MoveAction) action).target)];
      } else {
        PlaceNeutralsAction pn = (PlaceNeutralsAction) action;
        logits[a] = heads.pairU[cell(pn.pos1)] + heads.pairU[cell(pn.pos2)] + pairBias;
      }
      max = Math.max(max, logits[a]);
    }
    float[] p = new float[actions.size()];
    double sum = 0;
    for (int a = 0; a < p.length; a++) {
      double e = Math.exp(logits[a] - max);
      logits[a] = e;
      sum += e;
    }
    for (int a = 0; a < p.length; a++) {
      p[a] = (float) (logits[a] / sum);
    }
    return p;
  }

  private static int cell(Pos pos) {
    return pos.row * BOARD + pos.col;
  }

  /** Raw head outputs for one encoded position — package-private for the parity test. */
  static final class Heads {
    final double[] move; // [144]
    final double[] pairU; // [144]
    final double value; // mover-frame tanh value; NaN when the artifact has no value head

    Heads(double[] move, double[] pairU, double value) {
      this.move = move;
      this.pairU = pairU;
      this.value = value;
    }
  }

  double pairBias() {
    return pairBias;
  }

  /** Full forward pass from the compact training-time encoding (symbols + scalars). */
  Heads forward(int[] sym, int movesLeft, int nuOwn, int nuOpp) {
    double[] x = new double[PLANES * CELLS];
    for (int i = 0; i < CELLS; i++) {
      x[sym[i] * CELLS + i] = 1.0;
    }
    java.util.Arrays.fill(x, (8 + movesLeft - 1) * CELLS, (8 + movesLeft) * CELLS, 1.0);
    if (nuOwn != 0) {
      java.util.Arrays.fill(x, 11 * CELLS, 12 * CELLS, 1.0);
    }
    if (nuOpp != 0) {
      java.util.Arrays.fill(x, 12 * CELLS, 13 * CELLS, 1.0);
    }

    int in = PLANES;
    for (int layer = 0; layer < layers; layer++) {
      x = conv3x3Relu(x, in, convW[layer], convB[layer]);
      in = channels;
    }
    double[] move = head(x, moveW, moveB);
    double[] pairU = head(x, pairW, pairB);
    return new Heads(move, pairU, valueFc1W == null ? Double.NaN : value(x));
  }

  /** Value head on the final trunk activation: GAP per channel -> fc1 relu -> fc2 -> tanh. */
  private double value(double[] x) {
    double[] gap = new double[channels];
    for (int ch = 0; ch < channels; ch++) {
      double sum = 0;
      for (int i = 0; i < CELLS; i++) {
        sum += x[ch * CELLS + i];
      }
      gap[ch] = sum / CELLS;
    }
    int hidden = valueFc1B.length;
    double out = valueFc2B;
    for (int h = 0; h < hidden; h++) {
      double acc = valueFc1B[h];
      for (int ch = 0; ch < channels; ch++) {
        acc += valueFc1W[h * channels + ch] * gap[ch];
      }
      if (acc > 0) {
        out += valueFc2W[h] * acc;
      }
    }
    return Math.tanh(out);
  }

  private static final int PADDED = BOARD + 2;

  /**
   * 3x3 same-padding conv + ReLU: input [in][12][12] flat, weights [out][in*9] flat. Zero-pads to
   * 14x14, gathers each output cell's 3x3xin patch once (im2col), then runs stride-1 dot products
   * of length in*9 — the layout that lets the JIT vectorize, which is what keeps prior evaluation
   * cheap enough to sit inside MCTS expansion.
   */
  private double[] conv3x3Relu(double[] input, int inChannels, double[] w, double[] b) {
    double[] padded = new double[inChannels * PADDED * PADDED];
    for (int ic = 0; ic < inChannels; ic++) {
      for (int r = 0; r < BOARD; r++) {
        System.arraycopy(
            input,
            ic * CELLS + r * BOARD,
            padded,
            ic * PADDED * PADDED + (r + 1) * PADDED + 1,
            BOARD);
      }
    }
    // Patch matrix, cell-major: patches[cell * k + (ic*9 + kr*3 + kc)] — the exact layout of a
    // weight row, so the conv below is CELLS x out plain dot products.
    int k = inChannels * 9;
    double[] patches = new double[CELLS * k];
    for (int r = 0; r < BOARD; r++) {
      for (int c = 0; c < BOARD; c++) {
        int cellBase = (r * BOARD + c) * k;
        for (int ic = 0; ic < inChannels; ic++) {
          int pBase = ic * PADDED * PADDED;
          int dst = cellBase + ic * 9;
          for (int kr = 0; kr < 3; kr++) {
            int src = pBase + (r + kr) * PADDED + c;
            patches[dst + kr * 3] = padded[src];
            patches[dst + kr * 3 + 1] = padded[src + 1];
            patches[dst + kr * 3 + 2] = padded[src + 2];
          }
        }
      }
    }
    int out = b.length;
    double[] result = new double[out * CELLS];
    for (int o = 0; o < out; o++) {
      int wBase = o * k;
      int oBase = o * CELLS;
      for (int cell = 0; cell < CELLS; cell++) {
        int pBase = cell * k;
        // Four accumulators break the FMA dependency chain (~3x on scalar FP).
        // ponytail: float + jdk.incubator.vector is the next rung if Phase 2 needs more.
        double a0 = 0;
        double a1 = 0;
        double a2 = 0;
        double a3 = 0;
        int i = 0;
        for (; i + 4 <= k; i += 4) {
          a0 += w[wBase + i] * patches[pBase + i];
          a1 += w[wBase + i + 1] * patches[pBase + i + 1];
          a2 += w[wBase + i + 2] * patches[pBase + i + 2];
          a3 += w[wBase + i + 3] * patches[pBase + i + 3];
        }
        double acc = b[o] + a0 + a1 + a2 + a3;
        for (; i < k; i++) {
          acc += w[wBase + i] * patches[pBase + i];
        }
        result[oBase + cell] = acc > 0 ? acc : 0;
      }
    }
    return result;
  }

  /** 1x1 conv head to a single 12x12 map. */
  private double[] head(double[] x, double[] w, double bias) {
    double[] out = new double[CELLS];
    for (int i = 0; i < CELLS; i++) {
      double acc = bias;
      for (int ch = 0; ch < channels; ch++) {
        acc += w[ch] * x[ch * CELLS + i];
      }
      out[i] = acc;
    }
    return out;
  }

  /** Loads the trainer's export; validates shapes so a bad file fails here, once. */
  public static PolicyNetPrior load(Path weightsJson) throws IOException {
    JsonNode root = new ObjectMapper().readTree(Files.readAllBytes(weightsJson));
    JsonNode meta = root.path("meta");
    int board = meta.path("board").asInt(-1);
    int planes = meta.path("planes").asInt(-1);
    if (board != BOARD || planes != PLANES) {
      throw new IOException(
          "policy net: meta board/planes "
              + board
              + "/"
              + planes
              + " unsupported in "
              + weightsJson);
    }
    int channels = meta.path("channels").asInt(-1);
    int layers = meta.path("layers").asInt(-1);
    JsonNode conv = root.path("conv");
    if (channels <= 0 || layers <= 0 || !conv.isArray() || conv.size() != layers) {
      throw new IOException("policy net: bad meta/conv shape in " + weightsJson);
    }
    double[][] convW = new double[layers][];
    double[][] convB = new double[layers][];
    for (int layer = 0; layer < layers; layer++) {
      int inChannels = layer == 0 ? PLANES : channels;
      JsonNode w = conv.get(layer).path("w");
      if (!w.isArray() || w.size() != channels) {
        throw new IOException("policy net: conv[" + layer + "].w rows != " + channels);
      }
      convW[layer] = new double[channels * inChannels * 9];
      for (int o = 0; o < channels; o++) {
        JsonNode wo = w.get(o);
        if (!wo.isArray() || wo.size() != inChannels) {
          throw new IOException(
              "policy net: conv[" + layer + "].w[" + o + "] cols != " + inChannels);
        }
        for (int ic = 0; ic < inChannels; ic++) {
          JsonNode k = wo.get(ic);
          for (int kr = 0; kr < 3; kr++) {
            for (int kc = 0; kc < 3; kc++) {
              convW[layer][(o * inChannels + ic) * 9 + kr * 3 + kc] =
                  k.get(kr).get(kc).doubleValue();
            }
          }
        }
      }
      convB[layer] = vector(conv.get(layer).path("b"), channels, "conv.b", weightsJson);
    }
    double[] moveW = headWeights(root.path("move_head"), channels, weightsJson);
    double[] pairW = headWeights(root.path("pair_head"), channels, weightsJson);

    // Optional Phase 2 value head — absent in policy-only artifacts.
    double[] valueFc1W = null;
    double[] valueFc1B = null;
    double[] valueFc2W = null;
    double valueFc2B = 0;
    JsonNode vh = root.path("value_head");
    if (!vh.isMissingNode() && !vh.isNull()) {
      JsonNode fc1w = vh.path("fc1_w");
      if (!fc1w.isArray() || fc1w.size() == 0) {
        throw new IOException("policy net: value_head.fc1_w missing/empty in " + weightsJson);
      }
      int hidden = fc1w.size();
      valueFc1W = new double[hidden * channels];
      for (int h = 0; h < hidden; h++) {
        double[] row = vector(fc1w.get(h), channels, "value_head.fc1_w[" + h + "]", weightsJson);
        System.arraycopy(row, 0, valueFc1W, h * channels, channels);
      }
      valueFc1B = vector(vh.path("fc1_b"), hidden, "value_head.fc1_b", weightsJson);
      valueFc2W = vector(vh.path("fc2_w"), hidden, "value_head.fc2_w", weightsJson);
      JsonNode b2 = vh.path("fc2_b");
      if (!b2.isNumber() || !Double.isFinite(b2.doubleValue())) {
        throw new IOException("policy net: value_head.fc2_b not finite in " + weightsJson);
      }
      valueFc2B = b2.doubleValue();
    }
    return new PolicyNetPrior(
        channels,
        layers,
        convW,
        convB,
        moveW,
        root.path("move_head").path("b").doubleValue(),
        pairW,
        root.path("pair_head").path("b").doubleValue(),
        root.path("pair_bias").doubleValue(),
        valueFc1W,
        valueFc1B,
        valueFc2W,
        valueFc2B);
  }

  /** A 1x1-conv head's weights: torch shape [1][channels][1][1] flattened to [channels]. */
  private static double[] headWeights(JsonNode head, int channels, Path src) throws IOException {
    JsonNode w = head.path("w");
    if (!w.isArray() || w.size() != channels) {
      throw new IOException("policy net: head w != " + channels + " channels in " + src);
    }
    double[] out = new double[channels];
    for (int ch = 0; ch < channels; ch++) {
      JsonNode v = w.get(ch);
      // [channels][1][1] nesting from torch — unwrap to the scalar.
      while (v.isArray()) {
        v = v.get(0);
      }
      if (!v.isNumber() || !Double.isFinite(v.doubleValue())) {
        throw new IOException("policy net: head w[" + ch + "] not finite in " + src);
      }
      out[ch] = v.doubleValue();
    }
    return out;
  }

  private static double[] vector(JsonNode node, int n, String what, Path src) throws IOException {
    if (!node.isArray() || node.size() != n) {
      throw new IOException("policy net: " + what + " must have " + n + " entries in " + src);
    }
    double[] out = new double[n];
    for (int i = 0; i < n; i++) {
      JsonNode v = node.get(i);
      if (!v.isNumber() || !Double.isFinite(v.doubleValue())) {
        throw new IOException("policy net: " + what + "[" + i + "] not finite in " + src);
      }
      out[i] = v.doubleValue();
    }
    return out;
  }
}
