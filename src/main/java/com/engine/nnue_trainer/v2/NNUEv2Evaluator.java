package com.engine.nnue_trainer.v2;

import com.engine.nnue_trainer.board.Board;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Opt-in NNUE v2 leaf evaluator (bead d4a.1.3).
 *
 * <p>Loads the trained float-weights JSON (see python/v2/export_weights.py: {@code
 * stm_embed}/{@code nstm_embed} = [num_patterns x W], plus dense {@code l1} 2W+14->16, {@code l2}
 * 16->32, {@code l3} 32->1) and the promoted pattern dictionary. For a board it computes:
 *
 * <ul>
 *   <li>{@code stm_acc}(W) = sum of {@code stm_embed} rows over counted STM patterns
 *   <li>{@code nstm_acc}(W) = sum of {@code nstm_embed} rows over counted NSTM patterns
 * </ul>
 *
 * then concatenates {@code [stm_acc, nstm_acc, dense(14)]} (2062) and runs {@code l1 -> relu -> l2
 * -> relu -> l3} to a scalar leaf value in the side-to-move frame.
 *
 * <p>The accumulator/pattern math is reused from {@link NNUEv2Accumulator} — NOT reimplemented. Two
 * accumulator instances carry the two embeddings; a single shared count {@link
 * NNUEv2Accumulator.State} (counts are embedding-independent) feeds both, and we take the STM half
 * from the STM-embedding instance and the NSTM half from the NSTM-embedding instance.
 *
 * <p>Board-size agnostic: no 12x12 assumption; patterns + dense features come straight from the
 * board dimensions.
 */
public class NNUEv2Evaluator {

  public static final Path DEFAULT_WEIGHTS = Path.of("python", "v2", "nnue_v2_weights.json");
  public static final Path DEFAULT_DICT = Path.of("python", "v2", "nnue_v2_dictionary.json");
  private static final int DENSE = 14;

  private final NNUEv2Accumulator stmAcc; // built with stm_embed
  private final NNUEv2Accumulator nstmAcc; // built with nstm_embed
  private final int w;
  private final float[][] l1w;
  private final float[] l1b;
  private final float[][] l2w;
  private final float[] l2b;
  private final float[][] l3w;
  private final float[] l3b;

  public NNUEv2Evaluator(
      PatternDictionary dict,
      float[][] stmEmbed,
      float[][] nstmEmbed,
      float[][] l1w,
      float[] l1b,
      float[][] l2w,
      float[] l2b,
      float[][] l3w,
      float[] l3b) {
    Objects.requireNonNull(dict, "dict");
    this.w = stmEmbed[0].length;
    if (nstmEmbed[0].length != w) {
      throw new IllegalArgumentException("stm/nstm embedding widths differ");
    }
    if (l1w[0].length != 2 * w + DENSE) {
      throw new IllegalArgumentException(
          "l1 in-dim " + l1w[0].length + " != 2*W+" + DENSE + " (" + (2 * w + DENSE) + ")");
    }
    // NNUEv2Accumulator validates row-count == dict.numPatterns() and per-row width == W.
    this.stmAcc = new NNUEv2Accumulator(dict, stmEmbed, null, w, DENSE);
    this.nstmAcc = new NNUEv2Accumulator(dict, nstmEmbed, null, w, DENSE);
    this.l1w = l1w;
    this.l1b = l1b;
    this.l2w = l2w;
    this.l2b = l2b;
    this.l3w = l3w;
    this.l3b = l3b;
  }

  /** Leaf value from {@code stm}'s perspective (positive == good for {@code stm}). */
  public float evaluate(Board board, int stm, int turnNumber) {
    // One shared count state (embedding-independent). Reused by both accumulator instances.
    NNUEv2Accumulator.State state = stmAcc.newState(board, stm);
    float[] dense = DenseFeatures.extract(board, stm, turnNumber);

    // a = [stmEmbed*stmCounts, stmEmbed*nstmCounts, dense]  -> take first W  (correct STM acc)
    // b = [nstmEmbed*stmCounts, nstmEmbed*nstmCounts, dense] -> take next  W  (correct NSTM acc)
    float[] a = stmAcc.output(state, dense);
    float[] b = nstmAcc.output(state, dense);

    float[] x = new float[2 * w + DENSE];
    System.arraycopy(a, 0, x, 0, w);
    System.arraycopy(b, w, x, w, w);
    System.arraycopy(dense, 0, x, 2 * w, DENSE);

    float[] h1 = relu(linear(l1w, l1b, x));
    float[] h2 = relu(linear(l2w, l2b, h1));
    return linear(l3w, l3b, h2)[0];
  }

  /** turnNumber defaults to 0 (search has no per-node turn counter). */
  public float evaluate(Board board, int stm) {
    return evaluate(board, stm, 0);
  }

  /** y[o] = bias[o] + sum_i weight[o][i] * x[i]; weight is [out][in] (PyTorch nn.Linear). */
  private static float[] linear(float[][] weight, float[] bias, float[] x) {
    float[] y = new float[weight.length];
    for (int o = 0; o < weight.length; o++) {
      float[] row = weight[o];
      float sum = bias[o];
      for (int i = 0; i < row.length; i++) {
        sum += row[i] * x[i];
      }
      y[o] = sum;
    }
    return y;
  }

  private static float[] relu(float[] v) {
    for (int i = 0; i < v.length; i++) {
      if (v[i] < 0) {
        v[i] = 0;
      }
    }
    return v;
  }

  // ---- Loading -------------------------------------------------------------

  /** Loads weights + dictionary from the default committed/gitignored locations. */
  public static NNUEv2Evaluator load() throws IOException {
    return load(DEFAULT_WEIGHTS, DEFAULT_DICT);
  }

  /**
   * Streams the weights JSON (the blob is ~344MB of text; a full DOM parse would blow the heap, so
   * we walk it with the Jackson streaming parser and keep only the float arrays — final footprint
   * is 2x[num_patterns x W] floats ~= 66MB).
   */
  public static NNUEv2Evaluator load(Path weightsJson, Path dictJson) throws IOException {
    PatternDictionary dict = PatternDictionary.load(dictJson);
    float[][] stmEmbed = null;
    float[][] nstmEmbed = null;
    float[][] l1w = null;
    float[] l1b = null;
    float[][] l2w = null;
    float[] l2b = null;
    float[][] l3w = null;
    float[] l3b = null;

    JsonFactory factory = new JsonFactory();
    try (InputStream in = Files.newInputStream(weightsJson);
        JsonParser p = factory.createParser(in)) {
      expect(p.nextToken(), JsonToken.START_OBJECT, "top-level object");
      while (p.nextToken() != JsonToken.END_OBJECT) {
        String field = p.currentName();
        p.nextToken(); // move onto the value
        switch (field) {
          case "stm_embed":
            stmEmbed = read2d(p);
            break;
          case "nstm_embed":
            nstmEmbed = read2d(p);
            break;
          case "l1":
          case "l2":
          case "l3":
            {
              float[][] weight = null;
              float[] bias = null;
              expect(p.currentToken(), JsonToken.START_OBJECT, field + " object");
              while (p.nextToken() != JsonToken.END_OBJECT) {
                String sub = p.currentName();
                p.nextToken();
                if ("weight".equals(sub)) {
                  weight = read2d(p);
                } else if ("bias".equals(sub)) {
                  bias = read1d(p);
                } else {
                  p.skipChildren();
                }
              }
              if ("l1".equals(field)) {
                l1w = weight;
                l1b = bias;
              } else if ("l2".equals(field)) {
                l2w = weight;
                l2b = bias;
              } else {
                l3w = weight;
                l3b = bias;
              }
              break;
            }
          default:
            p.skipChildren();
        }
      }
    }

    if (stmEmbed == null || nstmEmbed == null || l1w == null || l2w == null || l3w == null) {
      throw new IOException("weights JSON missing required tensors: " + weightsJson);
    }
    return new NNUEv2Evaluator(dict, stmEmbed, nstmEmbed, l1w, l1b, l2w, l2b, l3w, l3b);
  }

  private static float[][] read2d(JsonParser p) throws IOException {
    expect(p.currentToken(), JsonToken.START_ARRAY, "2d array");
    List<float[]> rows = new ArrayList<>();
    while (p.nextToken() != JsonToken.END_ARRAY) {
      rows.add(read1d(p));
    }
    return rows.toArray(new float[0][]);
  }

  private static float[] read1d(JsonParser p) throws IOException {
    expect(p.currentToken(), JsonToken.START_ARRAY, "1d array");
    float[] buf = new float[16];
    int n = 0;
    while (p.nextToken() != JsonToken.END_ARRAY) {
      if (n == buf.length) {
        float[] grown = new float[buf.length * 2];
        System.arraycopy(buf, 0, grown, 0, n);
        buf = grown;
      }
      buf[n++] = p.getFloatValue();
    }
    if (n == buf.length) {
      return buf;
    }
    float[] exact = new float[n];
    System.arraycopy(buf, 0, exact, 0, n);
    return exact;
  }

  private static void expect(JsonToken got, JsonToken want, String what) throws IOException {
    if (got != want) {
      throw new IOException("expected " + want + " for " + what + " but got " + got);
    }
  }
}
