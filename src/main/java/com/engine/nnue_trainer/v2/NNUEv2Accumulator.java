package com.engine.nnue_trainer.v2;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.Pos;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
    Objects.requireNonNull(dict, "dict");
    if (hiddenWeights != null && hiddenWeights.length != dict.numPatterns()) {
      throw new IllegalArgumentException(
          "hiddenWeights row count does not match dict.numPatterns()");
    }
    if (hiddenWeights != null) {
      for (int r = 0; r < hiddenWeights.length; r++) {
        if (hiddenWeights[r] == null || hiddenWeights[r].length != K) {
          throw new IllegalArgumentException("hiddenWeights K dimension does not match expected K");
        }
      }
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
   * Incrementally-maintained per-perspective {@code id -> count} state. These integer maps are the
   * genuinely incremental structure; the float accumulator / output is DERIVED from them through the
   * same reduction {@link #computeFull} uses, so full and incremental paths are byte-identical.
   */
  public static class State {
    private final Map<Integer, Integer> stmCounts;
    private final Map<Integer, Integer> nstmCounts;
    private final int activePlayer;

    public State(Map<Integer, Integer> stmCounts, Map<Integer, Integer> nstmCounts, int activePlayer) {
      this.stmCounts = stmCounts;
      this.nstmCounts = nstmCounts;
      this.activePlayer = activePlayer;
    }

    public Map<Integer, Integer> stmCounts() {
      return stmCounts;
    }

    public Map<Integer, Integer> nstmCounts() {
      return nstmCounts;
    }

    public int activePlayer() {
      return activePlayer;
    }
  }

  /** Builds a fresh {@link State} by counting patterns for both perspectives (STM = activePlayer). */
  public State newState(Board board, int activePlayer) {
    return new State(
        countPatterns(board, activePlayer),
        countPatterns(board, 3 - activePlayer),
        activePlayer);
  }

  /**
   * Reduces one perspective's counts to a {@code float[K]} accumulator = bias + sum over ids in
   * ASCENDING sorted order of {@code count * col}. Null weights -> bias only; null bias -> zeros.
   * The deterministic sorted-id order is what makes full == incremental byte-exact.
   */
  private float[] accumFromCounts(Map<Integer, Integer> counts) {
    float[] a = new float[K];
    if (hiddenBias != null) {
      System.arraycopy(hiddenBias, 0, a, 0, K);
    }
    if (hiddenWeights == null) {
      return a;
    }
    List<Integer> ids = new ArrayList<>(counts.keySet());
    Collections.sort(ids);
    for (int id : ids) {
      float[] col = hiddenWeights[id];
      int count = counts.get(id);
      for (int i = 0; i < K; i++) {
        a[i] += count * col[i];
      }
    }
    return a;
  }

  /** Assembles the {@code [STM, NSTM, dense]} output from a {@link State} via {@link #accumFromCounts}. */
  public float[] output(State state, float[] denseFeatures) {
    return assemble(
        accumFromCounts(state.stmCounts), accumFromCounts(state.nstmCounts), denseFeatures);
  }

  /**
   * Dictionary id of the window centered at {@code (r,c)} for {@code owner}, or {@code -1} when the
   * window is unemitted (all empty/OOB) or a dictionary miss.
   */
  int idAt(Board board, int r, int c, int owner, Pos enemyBase) {
    PatternContract.Window w = PatternContract.buildWindow(board, r, c, owner, enemyBase);
    if (w == null) {
      return -1;
    }
    return dict.lookup(signature(w));
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

  /**
   * Incrementally updates {@code state} from {@code oldBoard} to {@code newBoard} given the changed
   * cells, keeping both count maps EXACTLY equal to a full recompute on {@code newBoard}. Counts are
   * weight-independent, so this runs regardless of null weights (the derived output stays correct).
   * Updates both STM (owner = activePlayer) and NSTM (owner = 3 - activePlayer) perspectives.
   */
  public void applyMove(
      State state, Board oldBoard, Board newBoard, Collection<Pos> changedCells) {
    int stm = state.activePlayer;
    applyPerspective(state.stmCounts, oldBoard, newBoard, stm, changedCells);
    applyPerspective(state.nstmCounts, oldBoard, newBoard, 3 - stm, changedCells);
  }

  private void applyPerspective(
      Map<Integer, Integer> counts,
      Board oldBoard,
      Board newBoard,
      int owner,
      Collection<Pos> changedCells) {
    Pos oldBase = PatternContract.findEnemyBase(oldBoard, owner);
    Pos newBase = PatternContract.findEnemyBase(newBoard, owner);
    if (!Objects.equals(oldBase, newBase)) {
      // The enemy base moved, which shifts every window's distance bucket: no local invariant holds,
      // so recompute this perspective in full.
      counts.clear();
      counts.putAll(countPatterns(newBoard, owner));
      return;
    }
    // Deduped union of window centers overlapping any changed cell (radius 2 == 5x5), so each
    // affected window is processed exactly once even when multiple cells share it.
    Set<Pos> centers = new HashSet<>();
    for (Pos changed : changedCells) {
      for (int wr = changed.row - 2; wr <= changed.row + 2; wr++) {
        for (int wc = changed.col - 2; wc <= changed.col + 2; wc++) {
          if (newBoard.isValidPos(wr, wc)) {
            centers.add(new Pos(wr, wc));
          }
        }
      }
    }
    for (Pos center : centers) {
      int oldId = idAt(oldBoard, center.row, center.col, owner, oldBase);
      int newId = idAt(newBoard, center.row, center.col, owner, newBase);
      if (oldId == newId) {
        continue;
      }
      if (oldId >= 0) {
        dec(counts, oldId);
      }
      if (newId >= 0) {
        counts.merge(newId, 1, Integer::sum);
      }
    }
  }

  /** Decrements {@code id}'s count, dropping the entry at zero (full recompute never stores zeros). */
  private static void dec(Map<Integer, Integer> counts, int id) {
    Integer v = counts.get(id);
    if (v == null) {
      return;
    }
    if (v <= 1) {
      counts.remove(id);
    } else {
      counts.put(id, v - 1);
    }
  }

  /** Cells differing by {@link Cell#equals} between two same-dimension boards. */
  public static List<Pos> diffCells(Board oldBoard, Board newBoard) {
    if (oldBoard.rows != newBoard.rows || oldBoard.cols != newBoard.cols) {
      throw new IllegalArgumentException("diffCells requires same-dimension boards");
    }
    List<Pos> diff = new ArrayList<>();
    for (int r = 0; r < oldBoard.rows; r++) {
      for (int c = 0; c < oldBoard.cols; c++) {
        if (!Objects.equals(oldBoard.getCell(r, c), newBoard.getCell(r, c))) {
          diff.add(new Pos(r, c));
        }
      }
    }
    return diff;
  }

  public float[] computeFull(Board board, int activePlayer, float[] denseFeatures) {
    // Full and incremental share the IDENTICAL float reduction (accumFromCounts over sorted ids),
    // guaranteeing byte-exact parity.
    return output(newState(board, activePlayer), denseFeatures);
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
