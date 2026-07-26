package com.engine.nnue_trainer.train;

import com.engine.nnue_trainer.search.eval.HandTunedEval;
import com.engine.nnue_trainer.v2.NNUEv2Accumulator;
import com.engine.nnue_trainer.v2.PatternContract;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Mines 5x5 patterns from the REAL games in games.db and scores each with the hand-tuned bot eval.
 *
 * <p>Pipeline (bd nnue-trainer-d4a.5.1): replay each game through the real engine via {@link
 * GamesDbReplay} (so virus-conversion/connectivity rules apply — not naive placement); at the board
 * state before every turn, scan all active 5x5 windows via {@link PatternContract} + {@link
 * NNUEv2Accumulator#signature} from the side-to-move's perspective, count per signature, and
 * attribute the position's {@link HandTunedEval} to every distinct pattern present. Emits {@code
 * games_db_pattern_stats.json} for the viz bead (d4a.5.2).
 *
 * <p>Conventions (see {@link GamesDbReplay}): a "position" is the board <b>before</b> a turn; STM =
 * that turn's player. Eval is STM-relative (positive = good for the side to move), {@code movesLeft
 * = }{@link GamesDbReplay#MOVES_LEFT}. Games are SKIPPED (not replayed) when pgn is
 * null/unparseable, termination is {@code illegal_move}/{@code disconnect}, the game has more than
 * 2 players, or replay throws.
 */
public final class GamesDbPatternMiner {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final int PATTERN_CAP =
      500; // patterns[] top-N by count (meta counts ALL distinct)
  private static final int TOP_EVAL_N = 50; // flagged top-N by |mean eval|
  private static final int TOP_EVAL_MIN_N = 5; // require this many positions before ranking by eval

  // Symbol code names, indexed by PatternContract symbol int (0..8).
  private static final String[] CODES = {
    "EMPTY",
    "NEUTRAL",
    "BASE_SELF",
    "BASE_OPPONENT",
    "NORMAL_SELF",
    "NORMAL_OPPONENT",
    "FORTIFIED_SELF",
    "FORTIFIED_OPPONENT",
    "OUT_OF_BOUNDS"
  };

  private GamesDbPatternMiner() {}

  /** Per-signature accumulator. */
  private static final class Acc {
    long count; // total window occurrences (per-window)
    long evalSum; // sum of position evals (one add per distinct-in-position)
    long evalN; // number of positions containing this pattern
    int[] symbols; // 25 codes (identical for a given signature)
    int distanceBucket;
    int id; // assigned after global ranking
  }

  public static void main(String[] args) throws Exception {
    Path db =
        Path.of(
            args.length > 0 ? args[0] : System.getenv().getOrDefault("NNUE_GAMES_DB", "games.db"));
    Path out = Path.of(args.length > 1 ? args[1] : "games_db_pattern_stats.json");
    if (!Files.exists(db)) {
      System.err.println("games.db not found: " + db);
      System.exit(1);
    }

    Map<String, Acc> accs = new java.util.HashMap<>();
    Map<String, Integer> boardSizes = new TreeMap<>();
    Map<String, Integer> skipReasons = new TreeMap<>();
    int gamesTotal = 0;
    int gamesUsed = 0;
    int gamesSkipped = 0;
    long positions = 0;
    long windowOccurrences = 0;

    try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
        Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT id, rows, cols, termination, pgn_content FROM games ORDER BY id")) {
      while (rs.next()) {
        gamesTotal++;
        int rows = rs.getInt("rows");
        int cols = rs.getInt("cols");
        String termination = rs.getString("termination");
        String pgn = rs.getString("pgn_content");

        JsonNode turns = null;
        try {
          turns = (pgn == null) ? null : MAPPER.readTree(pgn);
        } catch (Exception e) {
          turns = null;
        }
        if (turns == null || !turns.isArray() || turns.isEmpty()) {
          gamesSkipped++;
          bump(skipReasons, "no_pgn");
          continue;
        }
        if ("illegal_move".equals(termination) || "disconnect".equals(termination)) {
          gamesSkipped++;
          bump(skipReasons, "termination_" + termination);
          continue;
        }

        GamesDbReplay.Replay replay = GamesDbReplay.replay(rows, cols, turns);
        if (replay.skipReason != null) {
          // Prefixed: the DB's illegal_move TERMINATION and a replay rejection are different
          // failures, and only the second one means the rules port regressed.
          gamesSkipped++;
          bump(skipReasons, "replay_" + replay.skipReason);
          continue;
        }

        gamesUsed++;
        bump(boardSizes, rows + "x" + cols);
        for (GamesDbReplay.Snapshot s : replay.snapshots) {
          positions++;
          List<PatternContract.Window> windows = PatternContract.extractWindows(s.board, s.stm);
          int posEval =
              HandTunedEval.staticEval(s.board, s.stm, GamesDbReplay.MOVES_LEFT, s.neutralUsed);
          Set<String> distinctInPos = new HashSet<>();
          for (PatternContract.Window w : windows) {
            windowOccurrences++;
            String sig = NNUEv2Accumulator.signature(w);
            Acc a = accs.get(sig);
            if (a == null) {
              a = new Acc();
              a.symbols = w.symbols;
              a.distanceBucket = w.distanceBucket;
              accs.put(sig, a);
            }
            a.count++;
            if (distinctInPos.add(sig)) {
              a.evalSum += posEval;
              a.evalN++;
            }
          }
        }
      }
    }

    writeJson(
        out,
        accs,
        boardSizes,
        skipReasons,
        gamesTotal,
        gamesUsed,
        gamesSkipped,
        positions,
        windowOccurrences);

    // ---- headline report ----
    int promo5 = 0;
    int promo20 = 0;
    for (Acc a : accs.values()) {
      if (a.count >= 5) promo5++;
      if (a.count >= 20) promo20++;
    }
    System.out.println("=== games.db pattern mining ===");
    System.out.println("games_total   : " + gamesTotal);
    System.out.println("games_used    : " + gamesUsed);
    System.out.println("games_skipped : " + gamesSkipped + " " + skipReasons);
    System.out.println("board_sizes   : " + boardSizes);
    System.out.println("positions     : " + positions);
    System.out.println("window_occ    : " + windowOccurrences);
    System.out.println("distinct_pats : " + accs.size());
    System.out.println("freq >= 5     : " + promo5);
    System.out.println("freq >= 20    : " + promo20);
    System.out.println("output        : " + out.toAbsolutePath());
  }

  private static void bump(Map<String, Integer> m, String k) {
    m.merge(k, 1, Integer::sum);
  }

  private static void writeJson(
      Path out,
      Map<String, Acc> accs,
      Map<String, Integer> boardSizes,
      Map<String, Integer> skipReasons,
      int gamesTotal,
      int gamesUsed,
      int gamesSkipped,
      long positions,
      long windowOccurrences)
      throws Exception {
    // Deterministic global ranking: count desc, then signature asc.
    List<Map.Entry<String, Acc>> ranked = new ArrayList<>(accs.entrySet());
    ranked.sort(
        Comparator.<Map.Entry<String, Acc>>comparingLong(e -> -e.getValue().count)
            .thenComparing(Map.Entry::getKey));
    for (int i = 0; i < ranked.size(); i++) {
      ranked.get(i).getValue().id = i;
    }

    int promo5 = 0;
    int promo20 = 0;
    for (Acc a : accs.values()) {
      if (a.count >= 5) promo5++;
      if (a.count >= 20) promo20++;
    }

    ObjectNode root = MAPPER.createObjectNode();

    ObjectNode meta = root.putObject("meta");
    meta.put("games_total", gamesTotal);
    meta.put("games_used", gamesUsed);
    meta.put("games_skipped", gamesSkipped);
    meta.put("positions", positions);
    meta.put("window_occurrences", windowOccurrences);
    meta.put("distinct_patterns", accs.size());
    meta.put("pattern_cap", PATTERN_CAP);
    meta.put("moves_left_assumption", GamesDbReplay.MOVES_LEFT);
    meta.put(
        "sign_convention",
        "mean_handtuned_eval is STM-relative (positive = good for the side to move); "
            + "position = board before a turn; STM = that turn's player");
    ObjectNode sizes = meta.putObject("board_sizes");
    for (Map.Entry<String, Integer> e : boardSizes.entrySet()) {
      sizes.put(e.getKey(), e.getValue());
    }
    ObjectNode skips = meta.putObject("skip_reasons");
    for (Map.Entry<String, Integer> e : skipReasons.entrySet()) {
      skips.put(e.getKey(), e.getValue());
    }
    ObjectNode promo = meta.putObject("promo_counts");
    promo.put(">=5", promo5);
    promo.put(">=20", promo20);
    ArrayNode legend = meta.putArray("code_legend");
    for (String c : CODES) {
      legend.add(c);
    }

    // patterns[]: top PATTERN_CAP by count (already ranked).
    ArrayNode patterns = root.putArray("patterns");
    int cap = Math.min(PATTERN_CAP, ranked.size());
    for (int i = 0; i < cap; i++) {
      patterns.add(patternNode(ranked.get(i).getKey(), ranked.get(i).getValue()));
    }

    // top_by_eval: top TOP_EVAL_N by |mean| among patterns with eval_n >= TOP_EVAL_MIN_N.
    List<Acc> byEval = new ArrayList<>();
    for (Acc a : accs.values()) {
      if (a.evalN >= TOP_EVAL_MIN_N) {
        byEval.add(a);
      }
    }
    // Tie-break by id (already count-desc-then-signature): |mean| ties are common — 33 of the top
    // 50 share one value on the current corpus — so without this the cut is decided by HashMap
    // iteration order and the committed artifact churns for no reason.
    byEval.sort(
        Comparator.<Acc>comparingDouble(a -> -Math.abs(mean(a))).thenComparingInt(a -> a.id));
    ObjectNode topEvalMeta = root.putObject("top_by_eval_meta");
    topEvalMeta.put("min_eval_n", TOP_EVAL_MIN_N);
    topEvalMeta.put("top_n", TOP_EVAL_N);
    ArrayNode topEval = root.putArray("top_by_eval");
    // Signature lookup for id/symbols by identity is easier via ranked map; rebuild sig by id.
    String[] sigById = new String[ranked.size()];
    for (Map.Entry<String, Acc> e : ranked) {
      sigById[e.getValue().id] = e.getKey();
    }
    for (int i = 0; i < Math.min(TOP_EVAL_N, byEval.size()); i++) {
      Acc a = byEval.get(i);
      topEval.add(patternNode(sigById[a.id], a));
    }

    // freq_distribution: histogram buckets over ALL distinct patterns (rank-frequency friendly).
    int[] edges = {1, 2, 3, 5, 10, 20, 50, 100, 500, Integer.MAX_VALUE};
    ArrayNode freq = root.putArray("freq_distribution");
    long prevEdge = 1;
    for (int e = 1; e < edges.length; e++) {
      long lo = prevEdge;
      long hi = edges[e] - 1L; // inclusive upper bound of this bucket
      int n = 0;
      for (Acc a : accs.values()) {
        if (a.count >= lo && a.count <= hi) {
          n++;
        }
      }
      ObjectNode b = freq.addObject();
      b.put("min_count", lo);
      b.put("max_count", edges[e] == Integer.MAX_VALUE ? -1 : hi); // -1 == open ended
      b.put("num_patterns", n);
      prevEdge = edges[e];
    }

    Files.writeString(out, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n");
  }

  private static ObjectNode patternNode(String signature, Acc a) {
    ObjectNode p = MAPPER.createObjectNode();
    p.put("id", a.id);
    p.put("signature", signature);
    ArrayNode cells = p.putArray("cells");
    for (int r = 0; r < 5; r++) {
      ArrayNode row = cells.addArray();
      for (int c = 0; c < 5; c++) {
        row.add(CODES[a.symbols[r * 5 + c]]);
      }
    }
    p.put("distance_bucket", a.distanceBucket);
    p.put("count", a.count);
    p.put("mean_handtuned_eval", a.evalN == 0 ? 0.0 : mean(a));
    p.put("eval_n", a.evalN);
    return p;
  }

  private static double mean(Acc a) {
    return a.evalN == 0 ? 0.0 : (double) a.evalSum / (double) a.evalN;
  }
}
