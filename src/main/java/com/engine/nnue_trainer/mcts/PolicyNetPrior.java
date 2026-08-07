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

  private PolicyNetPrior(
      int channels,
      int layers,
      double[][] convW,
      double[][] convB,
      double[] moveW,
      double moveB,
      double[] pairW,
      double pairB,
      double pairBias) {
    this.channels = channels;
    this.layers = layers;
    this.convW = convW;
    this.convB = convB;
    this.moveW = moveW;
    this.moveB = moveB;
    this.pairW = pairW;
    this.pairB = pairB;
    this.pairBias = pairBias;
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

    Heads(double[] move, double[] pairU) {
      this.move = move;
      this.pairU = pairU;
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
    return new Heads(move, pairU);
  }

  /** 3x3 same-padding conv + ReLU: input [in][12][12] flat, weights [out][in*9] flat. */
  private double[] conv3x3Relu(double[] input, int inChannels, double[] w, double[] b) {
    int out = b.length;
    double[] result = new double[out * CELLS];
    for (int o = 0; o < out; o++) {
      int wBase = o * inChannels * 9;
      for (int r = 0; r < BOARD; r++) {
        for (int c = 0; c < BOARD; c++) {
          double acc = b[o];
          for (int ic = 0; ic < inChannels; ic++) {
            int wc = wBase + ic * 9;
            int icBase = ic * CELLS;
            for (int kr = -1; kr <= 1; kr++) {
              int rr = r + kr;
              if (rr < 0 || rr >= BOARD) {
                continue;
              }
              int rowBase = icBase + rr * BOARD;
              int kBase = wc + (kr + 1) * 3;
              for (int kc = -1; kc <= 1; kc++) {
                int cc = c + kc;
                if (cc < 0 || cc >= BOARD) {
                  continue;
                }
                acc += w[kBase + kc + 1] * input[rowBase + cc];
              }
            }
          }
          result[o * CELLS + r * BOARD + c] = acc > 0 ? acc : 0;
        }
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
    return new PolicyNetPrior(
        channels,
        layers,
        convW,
        convB,
        moveW,
        root.path("move_head").path("b").doubleValue(),
        pairW,
        root.path("pair_head").path("b").doubleValue(),
        root.path("pair_bias").doubleValue());
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
