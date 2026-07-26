package com.engine.nnue_trainer.train;

import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.search.eval.HandTunedEval;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Diagnostic for the strongly negative {@code baseline_mean_eval} that {@link V3FeatureMiner}
 * reports (bd nnue-trainer-d4a.6.1, Task 2). Every discrimination number is measured against that
 * baseline, so it has to be explained before the ridge fit is read.
 *
 * <p>Replays the same games.db positions as the miner and prints: the eval distribution (mean,
 * median, p10/p90, by ply bucket), the split by side-to-move player, the mover's stone-count
 * deficit, the mean eval under {@code movesLeft} 0..3, and an antisymmetry check ({@code eval(stm)
 * == -eval(other)} on the same board). Read-only — writes no artifact.
 *
 * <p>CLI: {@code V3EvalBaselineProbe [db-path]}, defaulting to {@code $NNUE_GAMES_DB} then {@code
 * ./games.db}.
 */
public final class V3EvalBaselineProbe {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private V3EvalBaselineProbe() {}

  /** One replayed position, reduced to the numbers this probe reports on. */
  static final class Row {
    int ply;
    int stm;
    int eval; // movesLeft = 3, the miner's assumption
    int evalOpponentFrame; // same scored player, tempo frame handed to the opponent
    int stmStones;
    int oppStones;
  }

  public static void main(String[] args) throws Exception {
    Path db =
        Path.of(
            args.length > 0 ? args[0] : System.getenv().getOrDefault("NNUE_GAMES_DB", "games.db"));
    if (!Files.exists(db)) {
      System.err.println("games.db not found: " + db);
      System.exit(1);
    }

    List<Row> rows = new ArrayList<>();
    long[] movesLeftSum = new long[4];
    int antisymmetryViolations = 0;
    int games = 0;
    // The probe exists to EXPLAIN the miner's baseline_mean_eval, which only means anything if it
    // sees the same positions. Without a denominator and reasons, a filter drifting apart from
    // V3FeatureMiner's would silently make every table below describe a different corpus.
    int gamesTotal = 0;
    Map<String, Integer> skipReasons = new TreeMap<>();
    // The same player's score before its turn vs after it: -eval(ply+1) is the ply-p mover's own
    // score once its turn is spent, so this is the value of ONE turn in eval units.
    long turnSwingSum = 0;
    int turnSwingCount = 0;

    try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
        Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT id, rows, cols, termination, pgn_content FROM games ORDER BY id")) {
      while (rs.next()) {
        gamesTotal++;
        int boardRows = rs.getInt("rows");
        int boardCols = rs.getInt("cols");
        String termination = rs.getString("termination");
        String pgn = rs.getString("pgn_content");
        if (boardRows != V3FeatureMiner.BOARD || boardCols != V3FeatureMiner.BOARD) {
          bump(skipReasons, "board_size");
          continue;
        }
        if ("illegal_move".equals(termination) || "disconnect".equals(termination)) {
          bump(skipReasons, "termination:" + termination);
          continue;
        }
        JsonNode turns;
        try {
          turns = (pgn == null) ? null : MAPPER.readTree(pgn);
        } catch (Exception e) {
          bump(skipReasons, "pgn_parse:" + e.getClass().getSimpleName());
          continue;
        }
        if (turns == null || !turns.isArray() || turns.isEmpty()) {
          bump(skipReasons, "no_turns");
          continue;
        }
        GamesDbReplay.Replay replay = GamesDbReplay.replay(boardRows, boardCols, turns);
        if (replay.skipReason != null) {
          bump(skipReasons, replay.skipReason);
          continue;
        }
        games++;

        int prevEval = 0;
        for (int ply = 0; ply < replay.snapshots.size(); ply++) {
          GamesDbReplay.Snapshot s = replay.snapshots.get(ply);
          int other = 3 - s.stm;
          Row row = new Row();
          row.ply = ply;
          row.stm = s.stm;
          row.eval =
              HandTunedEval.staticEval(s.board, s.stm, GamesDbReplay.MOVES_LEFT, s.neutralUsed);
          row.evalOpponentFrame =
              HandTunedEval.staticEval(
                  s.board, s.stm, other, GamesDbReplay.MOVES_LEFT, s.neutralUsed);
          for (int r = 0; r < s.board.rows; r++) {
            for (int c = 0; c < s.board.cols; c++) {
              Cell cell = s.board.getCell(r, c);
              if (cell.kind == CellKind.EMPTY || cell.kind == CellKind.BASE) {
                continue;
              }
              if (cell.owner == s.stm) {
                row.stmStones++;
              } else if (cell.owner == other) {
                row.oppStones++;
              }
            }
          }
          rows.add(row);
          if (ply > 0) {
            turnSwingSum += -(prevEval + row.eval);
            turnSwingCount++;
          }
          prevEval = row.eval;

          for (int ml = 0; ml <= 3; ml++) {
            movesLeftSum[ml] += HandTunedEval.staticEval(s.board, s.stm, ml, s.neutralUsed);
          }
          // NOT sign-convention evidence: with two ACTIVE players utility is exactly
          // raw(p) - raw(opponent), so antisymmetry is an algebraic identity that a globally
          // flipped eval would satisfy too. It only catches an eliminated player leaking in
          // (scored a flat -MATE_SCORE/2), which is the ±5e8 outlier the replay filters out.
          // The sign itself is pinned by HandTunedEvalSignConventionTest.
          int mirrored =
              HandTunedEval.staticEval(
                  s.board, other, s.stm, GamesDbReplay.MOVES_LEFT, s.neutralUsed);
          if (row.eval != -mirrored) {
            antisymmetryViolations++;
          }
        }
      }
    }

    int n = rows.size();
    if (n == 0) {
      System.out.println("no positions");
      return;
    }
    int[] evals = rows.stream().mapToInt(r -> r.eval).toArray();

    System.out.println(
        "=== v3 baseline_mean_eval probe (movesLeft="
            + GamesDbReplay.MOVES_LEFT
            + ", STM-relative) ===");
    System.out.println("games_used  : " + games + " / " + gamesTotal);
    System.out.println("games_skipped: " + (gamesTotal - games) + " " + skipReasons);
    System.out.println("positions   : " + n);
    printDistribution("all positions", evals);

    System.out.println("--- by ply bucket (ply = index of the turn within the game) ---");
    int maxPly = rows.stream().mapToInt(r -> r.ply).max().orElse(0);
    for (int lo = 0; lo <= maxPly; lo += 10) {
      final int a = lo;
      final int b = lo + 10;
      int[] bucket =
          rows.stream().filter(r -> r.ply >= a && r.ply < b).mapToInt(r -> r.eval).toArray();
      if (bucket.length > 0) {
        printDistribution("ply " + a + "-" + (b - 1), bucket);
      }
    }

    System.out.println("--- by side to move ---");
    for (int p = 1; p <= 2; p++) {
      final int player = p;
      int[] byPlayer = rows.stream().filter(r -> r.stm == player).mapToInt(r -> r.eval).toArray();
      if (byPlayer.length > 0) {
        printDistribution("stm=p" + p, byPlayer);
      }
    }

    System.out.println("--- stone counts (non-base owned cells) ---");
    double stmStones = rows.stream().mapToInt(r -> r.stmStones).average().orElse(0);
    double oppStones = rows.stream().mapToInt(r -> r.oppStones).average().orElse(0);
    System.out.printf(
        Locale.ROOT,
        "mean stones: stm=%.2f opp=%.2f deficit=%.2f%n",
        stmStones,
        oppStones,
        stmStones - oppStones);
    long stmBehind = rows.stream().filter(r -> r.stmStones < r.oppStones).count();
    System.out.printf(
        Locale.ROOT,
        "positions where the mover has fewer stones: %d / %d (%.1f%%)%n",
        stmBehind,
        n,
        100.0 * stmBehind / n);

    System.out.println("--- mean eval vs the movesLeft assumption ---");
    for (int ml = 0; ml <= 3; ml++) {
      System.out.printf(Locale.ROOT, "movesLeft=%d mean=%.2f%n", ml, (double) movesLeftSum[ml] / n);
    }

    System.out.println("--- mover-keyed terms (tempo frame handed to the opponent) ---");
    double opponentFrame = rows.stream().mapToInt(r -> r.evalOpponentFrame).average().orElse(0);
    double moverFrame = Arrays.stream(evals).average().orElse(0);
    System.out.printf(
        Locale.ROOT,
        "mean eval, mover frame=%.2f opponent frame=%.2f -> mover-keyed mass=%.2f%n",
        moverFrame,
        opponentFrame,
        moverFrame - opponentFrame);

    System.out.println("--- value of one turn ---");
    System.out.printf(
        Locale.ROOT,
        "mean turn swing (-(eval[p] + eval[p+1]), same player before vs after its turn) = %.2f "
            + "over %d turn pairs%n",
        (double) turnSwingSum / Math.max(1, turnSwingCount),
        turnSwingCount);
    int[] plyZero = rows.stream().filter(r -> r.ply == 0).mapToInt(r -> r.eval).toArray();
    if (plyZero.length > 0) {
      printDistribution("ply 0 (start)", plyZero);
    }

    System.out.println("--- sign convention ---");
    System.out.println(
        "antisymmetry violations (identity for 2 active players; >0 means an eliminated "
            + "player leaked into the corpus): "
            + antisymmetryViolations
            + " / "
            + n);
    long positive = Arrays.stream(evals).filter(e -> e > 0).count();
    System.out.printf(
        Locale.ROOT, "positive evals: %d / %d (%.1f%%)%n", positive, n, 100.0 * positive / n);
  }

  private static void bump(Map<String, Integer> counts, String reason) {
    counts.merge(reason, 1, Integer::sum);
  }

  private static void printDistribution(String label, int[] values) {
    int[] sorted = values.clone();
    Arrays.sort(sorted);
    double mean = Arrays.stream(sorted).average().orElse(0);
    System.out.printf(
        Locale.ROOT,
        "%-16s n=%-6d mean=%10.2f median=%10.2f p10=%10.2f p90=%10.2f min=%d max=%d%n",
        label,
        sorted.length,
        mean,
        (double) percentile(sorted, 50),
        (double) percentile(sorted, 10),
        (double) percentile(sorted, 90),
        sorted[0],
        sorted[sorted.length - 1]);
  }

  /** Nearest-rank percentile of an ascending array. */
  static int percentile(int[] sorted, int pct) {
    int i = (int) Math.ceil(pct / 100.0 * sorted.length) - 1;
    return sorted[Math.max(0, Math.min(sorted.length - 1, i))];
  }
}
