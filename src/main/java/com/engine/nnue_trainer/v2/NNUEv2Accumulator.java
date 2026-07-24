package com.engine.nnue_trainer.v2;

import com.engine.nnue_trainer.board.Board;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * v2 accumulator over the canonical 3.1 pattern contract. Builds window
 * signatures via {@link PatternContract#extractWindows} + {@link #signature},
 * looks them up in the promoted {@link PatternDictionary} (string keys), counts
 * occurrences per perspective (STM = active player, NSTM = 3 - active player),
 * ignores dictionary misses, and accumulates first-layer columns multiplicatively
 * (count * weight) into the two perspective accumulators (full recompute).
 */
public class NNUEv2Accumulator {
  private final PatternDictionary dict;
  private final float[][] hiddenWeights;
  private final float[] hiddenBias;
  private final int K;
  private final int denseSize;

  public NNUEv2Accumulator(
      PatternDictionary dict, float[][] hiddenWeights, float[] hiddenBias, int K) {
    this(dict, hiddenWeights, hiddenBias, K, 14);
  }

  public NNUEv2Accumulator(
      PatternDictionary dict,
      float[][] hiddenWeights,
      float[] hiddenBias,
      int K,
      int denseSize) {
    if (hiddenWeights != null && hiddenWeights.length > 0 && hiddenWeights[0].length != K) {
      throw new IllegalArgumentException("hiddenWeights K dimension does not match expected K");
    }
    if (hiddenBias != null && hiddenBias.length != K) {
      throw new IllegalArgumentException("hiddenBias dimension does not match expected K");
    }
    this.dict = dict;
    this.hiddenWeights = hiddenWeights;
    this.hiddenBias = hiddenBias;
    this.K = K;
    this.denseSize = denseSize;
  }

  /**
   * Canonical signature string for a window, byte-identical to
   * python/v2/mine_patterns.py::window_signature:
   * {@code ",".join(symbols) + "|" + distance_bucket}.
   */
  public static String signature(PatternContract.Window w) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < w.symbols.length; i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append(w.symbols[i]);
    }
    sb.append('|').append(w.distanceBucket);
    return sb.toString();
  }

  /**
   * Count dictionary-id occurrences over all windows for one perspective. Misses
   * (lookup == -1) are ignored. The same signature recurring across window centers
   * increments its count, so the result holds COUNTED occurrences, not booleans.
   */
  public Map<Integer, Integer> countPatterns(Board board, int perspectiveOwner) {
    Map<Integer, Integer> counts = new HashMap<>();
    List<PatternContract.Window> windows = PatternContract.extractWindows(board, perspectiveOwner);
    for (PatternContract.Window w : windows) {
      int id = dict.lookup(signature(w));
      if (id < 0) {
        continue;
      }
      counts.merge(id, 1, Integer::sum);
    }
    return counts;
  }

  public float[] computeFull(Board board, int activePlayer, float[] denseFeatures) {
    float[] accumSTM = new float[K];
    float[] accumNSTM = new float[K];

    if (hiddenBias != null) {
      System.arraycopy(hiddenBias, 0, accumSTM, 0, K);
      System.arraycopy(hiddenBias, 0, accumNSTM, 0, K);
    }

    if (hiddenWeights == null) {
      // No first-layer weights: accumulators stay at the bias (mirrors null bias
      // handling above). Skip the count/accumulate step entirely.
      return assemble(accumSTM, accumNSTM, denseFeatures);
    }

    int nstmPlayer = 3 - activePlayer;

    Map<Integer, Integer> stmCounts = countPatterns(board, activePlayer);
    Map<Integer, Integer> nstmCounts = countPatterns(board, nstmPlayer);

    for (Map.Entry<Integer, Integer> e : stmCounts.entrySet()) {
      float[] col = hiddenWeights[e.getKey()];
      int count = e.getValue();
      for (int i = 0; i < K; i++) {
        accumSTM[i] += count * col[i];
      }
    }
    for (Map.Entry<Integer, Integer> e : nstmCounts.entrySet()) {
      float[] col = hiddenWeights[e.getKey()];
      int count = e.getValue();
      for (int i = 0; i < K; i++) {
        accumNSTM[i] += count * col[i];
      }
    }

    return assemble(accumSTM, accumNSTM, denseFeatures);
  }

  private float[] assemble(float[] accumSTM, float[] accumNSTM, float[] denseFeatures) {
    if (denseFeatures == null) {
      denseFeatures = new float[denseSize];
    }
    float[] result = new float[K * 2 + denseSize];
    System.arraycopy(accumSTM, 0, result, 0, K);
    System.arraycopy(accumNSTM, 0, result, K, K);
    System.arraycopy(denseFeatures, 0, result, K * 2, denseSize);
    return result;
  }
}
