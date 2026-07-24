package com.engine.nnue_trainer.train;

import com.engine.nnue_trainer.train.GauntletMatch.Result;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Phase 3 Task 2: the promotion gate + champion store. The current champion IS the net in {@code
 * nnue_weights.json} (no separate pointer needed — the deployed file is the pointer). A challenger
 * is promoted only if it clears both bars: it beats the champion by a clear match margin AND it
 * does not fall below the hand-tuned bar the champion already clears. This guarantees the deployed
 * net never regresses.
 *
 * <p>Promotion mutates the champion weights atomically (write temp, then move — Task 4's safety
 * rule) and appends a W-L line to an append-only history log; rejection logs the rejected
 * challenger's W-L and leaves the champion untouched.
 */
public final class ChampionStore {

  /** Default promote margin: challenger must win the match by (wins − losses) ≥ 2. */
  public static final int DEFAULT_PROMOTE_MARGIN = 2;

  private final Path championWeights;
  private final Path historyLog;

  public ChampionStore(Path championWeights, Path historyLog) {
    this.championWeights = championWeights;
    this.historyLog = historyLog;
  }

  /** {@code PROMOTE_MARGIN} env override, else {@link #DEFAULT_PROMOTE_MARGIN}. */
  public static int promoteMarginFromEnv() {
    String v = System.getenv("PROMOTE_MARGIN");
    if (v == null || v.isBlank()) {
      return DEFAULT_PROMOTE_MARGIN;
    }
    return Integer.parseInt(v.trim());
  }

  /**
   * The pure promotion rule. Promote iff the challenger beats the champion by {@code promoteMargin}
   * or more, AND its margin against the hand-tuned bar is at least the champion's (i.e. it does not
   * lose to the bar by more than the champion does).
   */
  public static boolean shouldPromote(
      Result challengerVsChampion,
      Result challengerVsBar,
      Result championVsBar,
      int promoteMargin) {
    boolean beatsChampion = challengerVsChampion.margin() >= promoteMargin;
    boolean notBelowBar = challengerVsBar.margin() >= championVsBar.margin();
    return beatsChampion && notBelowBar;
  }

  /**
   * Promote the challenger: atomically overwrite the champion weights with it, then append a {@code
   * promoted=true} history line.
   */
  public void promote(int generation, Path challengerWeights, Result vsChampion, Result vsBar)
      throws IOException {
    atomicReplace(challengerWeights, championWeights);
    append(logLine(generation, true, vsChampion, vsBar));
  }

  /** Reject the challenger: leave the champion untouched, log its W-L for the record. */
  public void reject(int generation, Result vsChampion, Result vsBar) throws IOException {
    append(logLine(generation, false, vsChampion, vsBar));
  }

  private static String logLine(int gen, boolean promoted, Result vsChampion, Result vsBar) {
    return String.format(
        "gen=%d promoted=%s vsChampion=[%s] margin=%+d vsBar=%+d",
        gen, promoted, vsChampion, vsChampion.margin(), vsBar.margin());
  }

  private void append(String line) throws IOException {
    Path parent = historyLog.toAbsolutePath().getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.writeString(
        historyLog,
        line + System.lineSeparator(),
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND);
  }

  /**
   * Write to a temp file in the target's dir, then move over the target — never a torn weights
   * file.
   */
  static void atomicReplace(Path from, Path to) throws IOException {
    Path dir = to.toAbsolutePath().getParent();
    if (dir != null) {
      Files.createDirectories(dir);
    }
    Path tmp = Files.createTempFile(dir, "champion", ".tmp");
    Files.copy(from, tmp, StandardCopyOption.REPLACE_EXISTING);
    try {
      Files.move(tmp, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException | UnsupportedOperationException e) {
      // ponytail: atomic move unsupported on some filesystems — fall back to a plain move.
      Files.move(tmp, to, StandardCopyOption.REPLACE_EXISTING);
    }
  }
}
