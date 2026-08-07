package com.engine.nnue_trainer.search.gobot;

import com.engine.nnue_trainer.board.Action;
import com.engine.nnue_trainer.board.MoveAction;
import com.engine.nnue_trainer.board.PlaceNeutralsAction;
import com.engine.nnue_trainer.board.Pos;

/**
 * Packed-long array transposition table for the ENHANCED search path (plan item 2). Fixed-size,
 * power-of-two, allocation-free probe/store; depth-preferred + generation-aged replacement. The
 * parity-oracle path ({@code chooseDepth}) and {@code maxN} keep the original {@code HashMap<Long,
 * TableEntry>} untouched.
 *
 * <p>Entry packing (64 bits): score 32 | depth 6 | flag 2 | generation 6 | action 18. Scores are
 * root-relative and ply-independent EXCEPT mate scores, which encode distance-to-root — those are
 * rebased to node-relative distance on store ({@link #toStoredScore}) and back to the probing
 * node's ply on probe ({@link #fromStoredScore}), the standard mate-in-TT correction.
 *
 * <p>Action packing (18 bits): bit 17 = present, bit 16 = type (0 move / 1 neutral pair). Move:
 * bits 0-15 = cell index. Neutral pair: bits 0-7 / 8-15 = the two cell indices, normalized
 * ascending ({@code PlaceNeutralsAction.equals} is unordered). Unencodable actions (board too
 * large) store as absent — safe, just loses one ordering hint.
 */
final class GoTranspositionTable {

  /** Default size: 2^21 entries = 32 MB (two long arrays). */
  static final int DEFAULT_LOG2_SIZE = 21;

  /** Scores beyond this magnitude are mate scores and carry a ply distance. */
  static final long MATE_BAND = GoBotSearcher.MATE_SCORE - 1000;

  private static final int ACTION_PRESENT = 1 << 17;
  private static final int ACTION_NEUTRAL = 1 << 16;

  private final long[] keys;
  private final long[] data;
  private final int mask;
  private int generation; // 0..63, bumped once per choose* call

  GoTranspositionTable() {
    this(DEFAULT_LOG2_SIZE);
  }

  GoTranspositionTable(int log2Size) {
    int size = 1 << log2Size;
    this.keys = new long[size];
    this.data = new long[size];
    this.mask = size - 1;
  }

  /** New search (one per choose* call): age everything currently stored by one generation. */
  void bumpGeneration() {
    generation = (generation + 1) & 63;
  }

  /** Packed entry for {@code key}, or 0 on a miss (stored entries always have depth ≥ 1). */
  long probe(long key) {
    int i = (int) key & mask;
    return keys[i] == key ? data[i] : 0L;
  }

  /**
   * Depth-preferred, generation-aged replacement: always replace an empty slot, the same key, or
   * anything from an older generation; within the current generation keep the deeper entry.
   */
  void store(long key, int depth, int flag, int score, int actionBits) {
    int i = (int) key & mask;
    long old = data[i];
    if (old != 0L && keys[i] != key && genOf(old) == generation && depthOf(old) > depth) {
      return; // same-generation deeper entry for a different position wins the slot
    }
    keys[i] = key;
    data[i] =
        (score & 0xFFFFFFFFL)
            | ((long) (depth & 63) << 32)
            | ((long) (flag & 3) << 38)
            | ((long) generation << 40)
            | ((long) (actionBits & 0x3FFFF) << 46);
  }

  static int scoreOf(long entry) {
    return (int) entry;
  }

  static int depthOf(long entry) {
    return (int) (entry >>> 32) & 63;
  }

  static int flagOf(long entry) {
    return (int) (entry >>> 38) & 3;
  }

  static int genOf(long entry) {
    return (int) (entry >>> 40) & 63;
  }

  static int actionBitsOf(long entry) {
    return (int) (entry >>> 46) & 0x3FFFF;
  }

  // --- mate-score ply rebasing ---

  /** Root-relative score at {@code ply} → stored (node-relative for mate scores). */
  static int toStoredScore(long score, int ply) {
    if (score > MATE_BAND) {
      return (int) (score + ply);
    }
    if (score < -MATE_BAND) {
      return (int) (score - ply);
    }
    return (int) score;
  }

  /** Stored score → root-relative at the probing node's {@code ply}. */
  static long fromStoredScore(int stored, int ply) {
    if (stored > MATE_BAND) {
      return stored - (long) ply;
    }
    if (stored < -MATE_BAND) {
      return stored + (long) ply;
    }
    return stored;
  }

  // --- action packing ---

  /** 18-bit encoding of {@code action} for a {@code cols}-wide board, or 0 if unencodable. */
  static int encodeAction(Action action, int cols, int cells) {
    if (action instanceof MoveAction) {
      Pos t = ((MoveAction) action).target;
      int idx = t.row * cols + t.col;
      if (idx < 0 || idx >= cells || idx > 0xFFFF) {
        return 0;
      }
      return ACTION_PRESENT | idx;
    }
    if (action instanceof PlaceNeutralsAction) {
      PlaceNeutralsAction pn = (PlaceNeutralsAction) action;
      int a = pn.pos1.row * cols + pn.pos1.col;
      int b = pn.pos2.row * cols + pn.pos2.col;
      int lo = Math.min(a, b);
      int hi = Math.max(a, b);
      if (lo < 0 || hi >= cells || hi > 0xFF) {
        return 0; // two 8-bit indices only — boards above 256 cells lose the hint
      }
      return ACTION_PRESENT | ACTION_NEUTRAL | (hi << 8) | lo;
    }
    return 0;
  }

  /** Inverse of {@link #encodeAction}; {@code null} when absent. */
  static Action decodeAction(int bits, int cols) {
    if ((bits & ACTION_PRESENT) == 0) {
      return null;
    }
    if ((bits & ACTION_NEUTRAL) == 0) {
      int idx = bits & 0xFFFF;
      return new MoveAction(new Pos(idx / cols, idx % cols));
    }
    int lo = bits & 0xFF;
    int hi = (bits >>> 8) & 0xFF;
    return new PlaceNeutralsAction(new Pos(lo / cols, lo % cols), new Pos(hi / cols, hi % cols));
  }
}
