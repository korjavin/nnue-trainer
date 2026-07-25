package com.engine.nnue_trainer.train;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.search.eval.HandTunedEval;
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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Mines NNUE v3 candidate features — absolute {@code (row, col, cell-state)} on a fixed 12x12 board
 * — from the REAL games in games.db, and ranks them by <b>eval-discrimination</b>.
 *
 * <p>Pipeline (bd nnue-trainer-d4a.6.5, the v3.0 gate): replay each 12x12 game through the real
 * engine via {@link GamesDbReplay}; at the board before every turn score it with {@link
 * HandTunedEval} from the side-to-move's perspective, then for each of the 144 cells resolve the
 * STM-normalized state via {@link PatternContract#getSymbol} and attribute the position's eval to
 * that {@code (row, col, state)} feature. Emits {@code nnue_v3_feature_stats.json} for the preview
 * page.
 *
 * <p>Ranking is by {@code |mean_eval - baseline_mean_eval|}, NOT by frequency: frequency-ranking is
 * the v2 bug (it promotes the most common shapes, which are the most trivial ones). Support is a
 * <b>floor</b> only — features below it keep their stats but get {@code rank = -1}.
 *
 * <p>CLI: {@code V3FeatureMiner [db-path] [out-path] [--min-support N]}.
 */
public final class V3FeatureMiner {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** v3 fixes the board size; anything else is skipped as {@code wrong_board_size}. */
  public static final int BOARD = 12;

  /**
   * On-board cells are always one of PatternContract 0..7 ({@code OUT_OF_BOUNDS} is unreachable).
   */
  public static final int STATES = 8;

  static final String[] STATE_NAMES = {
    "EMPTY",
    "NEUTRAL",
    "BASE_SELF",
    "BASE_OPPONENT",
    "NORMAL_SELF",
    "NORMAL_OPPONENT",
    "FORTIFIED_SELF",
    "FORTIFIED_OPPONENT"
  };

  private V3FeatureMiner() {}

  /** One ranked {@code (row, col, state)} feature. {@code rank == -1} means below the floor. */
  public static final class Feature {
    public final int row;
    public final int col;
    public final int state;
    public final long support;
    public final double meanEval;
    public final double discrimination;
    public int rank = -1;

    Feature(int row, int col, int state, long support, double meanEval, double discrimination) {
      this.row = row;
      this.col = col;
      this.state = state;
      this.support = support;
      this.meanEval = meanEval;
      this.discrimination = discrimination;
    }

    public String stateName() {
      return STATE_NAMES[state];
    }
  }

  /**
   * Accumulates per-{@code (row, col, state)} support and eval sums over positions. Exactly one
   * state is active per cell per position, so a cell's support summed over its 8 states equals
   * {@link #positions()}.
   */
  public static final class Stats {
    private final long[] support = new long[BOARD * BOARD * STATES];
    private final long[] evalSum = new long[BOARD * BOARD * STATES];
    private long positions;
    private long baselineEvalSum;

    private static int idx(int r, int c, int state) {
      return (r * BOARD + c) * STATES + state;
    }

    /**
     * Accumulate one position: the board before a turn, its side to move, and its STM-relative
     * eval.
     */
    public void add(Board board, int stm, int eval) {
      // Off-board cells resolve to OUT_OF_BOUNDS (8), which idx() would fold into the NEXT cell's
      // EMPTY bucket — silent corruption everywhere but (11,11), where it throws. main() filters on
      // board size; this makes that a precondition rather than a convention.
      if (board.rows < BOARD || board.cols < BOARD) {
        throw new IllegalArgumentException(
            "v3 features need a >="
                + BOARD
                + "x"
                + BOARD
                + " board, got "
                + board.rows
                + "x"
                + board.cols);
      }
      positions++;
      baselineEvalSum += eval;
      for (int r = 0; r < BOARD; r++) {
        for (int c = 0; c < BOARD; c++) {
          int state = PatternContract.getSymbol(board.getCell(r, c), stm);
          int i = idx(r, c, state);
          support[i]++;
          evalSum[i] += eval;
        }
      }
    }

    public long positions() {
      return positions;
    }

    public double baselineMeanEval() {
      return positions == 0 ? 0.0 : (double) baselineEvalSum / (double) positions;
    }

    /** Support floor default: ~1% of positions, never below 30. */
    public int defaultSupportFloor() {
      return (int) Math.max(30L, positions / 100L);
    }

    /**
     * All observed features (support &gt; 0). Those meeting {@code minSupport} come first, ranked
     * by discrimination descending (ties by row, col, state); the rest follow in (row, col, state)
     * order with {@code rank == -1}.
     */
    public List<Feature> ranked(int minSupport) {
      double baseline = baselineMeanEval();
      List<Feature> above = new ArrayList<>();
      List<Feature> below = new ArrayList<>();
      for (int r = 0; r < BOARD; r++) {
        for (int c = 0; c < BOARD; c++) {
          for (int s = 0; s < STATES; s++) {
            long sup = support[idx(r, c, s)];
            if (sup == 0) {
              continue;
            }
            double mean = (double) evalSum[idx(r, c, s)] / (double) sup;
            Feature f = new Feature(r, c, s, sup, mean, Math.abs(mean - baseline));
            (sup >= minSupport ? above : below).add(f);
          }
        }
      }
      above.sort(
          Comparator.comparingDouble((Feature f) -> -f.discrimination)
              .thenComparingInt(f -> f.row)
              .thenComparingInt(f -> f.col)
              .thenComparingInt(f -> f.state));
      for (int i = 0; i < above.size(); i++) {
        above.get(i).rank = i;
      }
      above.addAll(below);
      return above;
    }
  }

  public static void main(String[] args) throws Exception {
    Path db = Path.of("/home/iv/games.db");
    Path out = Path.of("nnue_v3_feature_stats.json");
    Integer minSupportFlag = null;
    List<String> positional = new ArrayList<>();
    for (int i = 0; i < args.length; i++) {
      if ("--min-support".equals(args[i])) {
        if (i + 1 >= args.length) {
          System.err.println("--min-support needs a value");
          System.exit(1);
        }
        String raw = args[++i];
        try {
          minSupportFlag = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
          System.err.println("--min-support needs an integer, got: " + raw);
          System.exit(1);
        }
      } else {
        positional.add(args[i]);
      }
    }
    if (!positional.isEmpty()) {
      db = Path.of(positional.get(0));
    }
    if (positional.size() > 1) {
      out = Path.of(positional.get(1));
    }
    if (!Files.exists(db)) {
      System.err.println("games.db not found: " + db);
      System.exit(1);
    }

    Stats stats = new Stats();
    Map<String, Integer> skipReasons = new TreeMap<>();
    int gamesTotal = 0;
    int gamesUsed = 0;
    int gamesSkipped = 0;

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

        if (rows != BOARD || cols != BOARD) {
          gamesSkipped++;
          bump(skipReasons, "wrong_board_size");
          continue;
        }

        JsonNode turns;
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
          bump(skipReasons, termination);
          continue;
        }

        GamesDbReplay.Replay replay = GamesDbReplay.replay(rows, cols, turns);
        if (replay.skipReason != null) {
          gamesSkipped++;
          bump(skipReasons, replay.skipReason);
          continue;
        }

        gamesUsed++;
        for (GamesDbReplay.Snapshot s : replay.snapshots) {
          stats.add(
              s.board,
              s.stm,
              HandTunedEval.staticEval(s.board, s.stm, GamesDbReplay.MOVES_LEFT, s.neutralUsed));
        }
      }
    }

    int floor = minSupportFlag != null ? minSupportFlag : stats.defaultSupportFloor();
    List<Feature> features = stats.ranked(floor);
    writeJson(
        out,
        stats,
        features,
        floor,
        minSupportFlag != null ? "flag" : "default",
        skipReasons,
        gamesTotal,
        gamesUsed,
        gamesSkipped);

    int aboveFloor = 0;
    for (Feature f : features) {
      if (f.rank >= 0) {
        aboveFloor++;
      }
    }
    System.out.println("=== nnue v3 feature mining (absolute r,c,state on 12x12) ===");
    System.out.println("games_total        : " + gamesTotal);
    System.out.println("games_used         : " + gamesUsed);
    System.out.println("games_skipped      : " + gamesSkipped + " " + skipReasons);
    System.out.println("positions          : " + stats.positions());
    System.out.println("baseline_mean_eval : " + stats.baselineMeanEval());
    System.out.println("support_floor      : " + floor);
    System.out.println("features_observed  : " + features.size());
    System.out.println("features_above_floor: " + aboveFloor);
    System.out.println("--- top 10 by discrimination (NOT by frequency) ---");
    for (int i = 0; i < Math.min(10, aboveFloor); i++) {
      Feature f = features.get(i);
      System.out.printf(
          "#%-2d (%2d,%2d) %-18s discrim=%8.2f mean_eval=%9.2f support=%d%n",
          f.rank + 1, f.row, f.col, f.stateName(), f.discrimination, f.meanEval, f.support);
    }
    System.out.println("output             : " + out.toAbsolutePath());
  }

  private static void bump(Map<String, Integer> m, String k) {
    m.merge(k, 1, Integer::sum);
  }

  static void writeJson(
      Path out,
      Stats stats,
      List<Feature> features,
      int supportFloor,
      String supportFloorSource,
      Map<String, Integer> skipReasons,
      int gamesTotal,
      int gamesUsed,
      int gamesSkipped)
      throws Exception {
    ObjectNode root = MAPPER.createObjectNode();

    ObjectNode meta = root.putObject("meta");
    meta.put("games_total", gamesTotal);
    meta.put("games_used", gamesUsed);
    meta.put("games_skipped", gamesSkipped);
    ObjectNode skips = meta.putObject("skip_reasons");
    for (Map.Entry<String, Integer> e : skipReasons.entrySet()) {
      skips.put(e.getKey(), e.getValue());
    }
    meta.put("board_filter", BOARD + "x" + BOARD);
    meta.put("positions", stats.positions());
    meta.put("baseline_mean_eval", stats.baselineMeanEval());
    meta.put("support_floor", supportFloor);
    meta.put("support_floor_source", supportFloorSource);
    meta.put("moves_left_assumption", GamesDbReplay.MOVES_LEFT);
    meta.put("feature_count", features.size());
    meta.put(
        "ranking",
        "features are ranked by discrimination = |mean_eval - baseline_mean_eval|, NOT by "
            + "frequency; support is a floor only (rank = -1 below it)");
    ArrayNode legend = meta.putArray("state_legend");
    for (String s : STATE_NAMES) {
      legend.add(s);
    }

    ArrayNode arr = root.putArray("features");
    for (Feature f : features) {
      ObjectNode n = arr.addObject();
      n.put("row", f.row);
      n.put("col", f.col);
      n.put("state", f.state);
      n.put("state_name", f.stateName());
      n.put("support", f.support);
      n.put("mean_eval", f.meanEval);
      n.put("discrimination", f.discrimination);
      n.put("rank", f.rank);
      if (f.rank < 0) {
        n.put("below_support_floor", true);
      }
    }

    Files.writeString(out, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n");
  }
}
