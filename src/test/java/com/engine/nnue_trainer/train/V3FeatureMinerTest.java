package com.engine.nnue_trainer.train;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.v2.PatternContract;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Accumulation + discrimination-ranking math of the v3 feature miner. */
public class V3FeatureMinerTest {

  @TempDir Path tempDir;

  private static final String[] GAME_IDS = {
    "00e60d2e-4a2d-41c6-993f-7fc8c71f35b1", "01297459-5cc3-4488-840d-2d800b749ab8"
  };

  private static Board board12() {
    return new Board(V3FeatureMiner.BOARD, V3FeatureMiner.BOARD);
  }

  private static V3FeatureMiner.Feature find(List<V3FeatureMiner.Feature> fs, int r, int c, int s) {
    return fs.stream()
        .filter(f -> f.row == r && f.col == c && f.state == s)
        .findFirst()
        .orElseThrow(() -> new AssertionError("no feature " + r + "," + c + "," + s));
  }

  @Test
  public void testMeanEvalDiscriminationAndSupportFloor() {
    V3FeatureMiner.Stats stats = new V3FeatureMiner.Stats();

    // Two positions with a p1 stone at (3,4); one plain position. Evals 100, 200, 0 -> baseline
    // 100.
    Board withStone = board12();
    withStone.setCell(3, 4, new Cell(1, CellKind.NORMAL));
    stats.add(withStone, 1, 100);
    stats.add(withStone, 1, 200);
    stats.add(board12(), 1, 0);

    assertEquals(3, stats.positions());
    assertEquals(100.0, stats.baselineMeanEval(), 1e-9);

    List<V3FeatureMiner.Feature> fs = stats.ranked(2);

    // (3,4,NORMAL_SELF) is active in the two 100/200 positions: mean 150, discrimination 50.
    V3FeatureMiner.Feature stone = find(fs, 3, 4, PatternContract.NORMAL_SELF);
    assertEquals(2, stone.support);
    assertEquals(150.0, stone.meanEval, 1e-9);
    assertEquals(50.0, stone.discrimination, 1e-9);
    assertTrue(stone.rank >= 0);

    // The same cell EMPTY only occurs in the single eval-0 position: below the floor of 2.
    V3FeatureMiner.Feature empty = find(fs, 3, 4, PatternContract.EMPTY);
    assertEquals(1, empty.support);
    assertEquals(0.0, empty.meanEval, 1e-9);
    assertEquals(100.0, empty.discrimination, 1e-9);
    assertEquals(-1, empty.rank);

    // A cell untouched in every position is EMPTY in all 3 -> discrimination 0, still ranked.
    V3FeatureMiner.Feature everywhere = find(fs, 0, 0, PatternContract.EMPTY);
    assertEquals(3, everywhere.support);
    assertEquals(0.0, everywhere.discrimination, 1e-9);

    // Exactly one state is active per cell per position.
    long cell34 = fs.stream().filter(f -> f.row == 3 && f.col == 4).mapToLong(f -> f.support).sum();
    assertEquals(stats.positions(), cell34);
  }

  @Test
  public void testRankingIsByDiscriminationNotSupport() {
    V3FeatureMiner.Stats stats = new V3FeatureMiner.Stats();

    // (1,1) holds a stone in 8 low-swing positions; (2,2) in only 3 wildly-swinging ones.
    for (int i = 0; i < 8; i++) {
      Board b = board12();
      b.setCell(1, 1, new Cell(1, CellKind.NORMAL));
      stats.add(b, 1, 10);
    }
    for (int i = 0; i < 3; i++) {
      Board b = board12();
      b.setCell(2, 2, new Cell(1, CellKind.NORMAL));
      stats.add(b, 1, 1000);
    }

    List<V3FeatureMiner.Feature> fs = stats.ranked(3);
    V3FeatureMiner.Feature common = find(fs, 1, 1, PatternContract.NORMAL_SELF);
    V3FeatureMiner.Feature rare = find(fs, 2, 2, PatternContract.NORMAL_SELF);

    assertTrue(common.support > rare.support, "the low-discrimination feature is the common one");
    assertTrue(rare.discrimination > common.discrimination);
    assertTrue(rare.rank < common.rank, "rare-but-discriminative must outrank common-but-flat");

    // Ranks are dense, ordered by discrimination descending, and -1 only below the floor.
    double prev = Double.MAX_VALUE;
    int expected = 0;
    for (V3FeatureMiner.Feature f : fs) {
      if (f.rank < 0) {
        assertTrue(f.support < 3);
        continue;
      }
      assertEquals(expected++, f.rank);
      assertTrue(f.discrimination <= prev + 1e-9);
      prev = f.discrimination;
    }
  }

  @Test
  public void testFloorAboveEverySupportLeavesNothingRanked() {
    V3FeatureMiner.Stats stats = new V3FeatureMiner.Stats();
    stats.add(board12(), 1, 42);
    stats.add(board12(), 1, 42);

    List<V3FeatureMiner.Feature> fs = stats.ranked(3);
    assertEquals(V3FeatureMiner.BOARD * V3FeatureMiner.BOARD, fs.size(), "144 EMPTY features");
    for (V3FeatureMiner.Feature f : fs) {
      assertEquals(-1, f.rank, "nothing clears a floor above max support");
      assertEquals(2, f.support);
    }
  }

  /**
   * The committed {@code nnue_v3_feature_stats.json} is what the owner actually gates on, and it is
   * regenerated by hand from a games.db that lives outside the repo — so the invariants the plan
   * states about it are asserted here rather than left to the eye.
   */
  @Test
  public void testCommittedStatsArtifactInvariants() throws Exception {
    Path stats = Path.of("nnue_v3_feature_stats.json");
    assertTrue(Files.exists(stats), "missing " + stats);
    JsonNode root = new ObjectMapper().readTree(stats.toFile());

    JsonNode meta = root.path("meta");
    for (String key :
        List.of(
            "games_total",
            "games_used",
            "games_skipped",
            "skip_reasons",
            "board_filter",
            "positions",
            "baseline_mean_eval",
            "support_floor",
            "support_floor_source",
            "moves_left_assumption",
            "feature_count",
            "ranking",
            "state_legend")) {
      assertTrue(meta.has(key), "meta." + key + " missing — the page and gate doc read it");
    }
    assertEquals(
        V3FeatureMiner.BOARD + "x" + V3FeatureMiner.BOARD, meta.path("board_filter").asText());
    assertEquals(GamesDbReplay.MOVES_LEFT, meta.path("moves_left_assumption").asInt());
    assertEquals(V3FeatureMiner.STATE_NAMES.length, meta.path("state_legend").size());

    JsonNode features = root.path("features");
    long positions = meta.path("positions").asLong();
    int floor = meta.path("support_floor").asInt();
    assertEquals(features.size(), meta.path("feature_count").asInt());
    assertTrue(positions > 0 && features.size() > 0, "artifact must not be empty");

    // Ranked block first, dense and discrimination-descending; everything below the floor is -1.
    double prev = Double.MAX_VALUE;
    int expected = 0;
    boolean seenUnranked = false;
    long[] perCellSupport = new long[V3FeatureMiner.BOARD * V3FeatureMiner.BOARD];
    for (JsonNode f : features) {
      int rank = f.path("rank").asInt();
      long support = f.path("support").asLong();
      perCellSupport[f.path("row").asInt() * V3FeatureMiner.BOARD + f.path("col").asInt()] +=
          support;
      assertTrue(support > 0, "unobserved features must not be emitted");
      assertEquals(
          V3FeatureMiner.STATE_NAMES[f.path("state").asInt()], f.path("state_name").asText());
      if (rank < 0) {
        seenUnranked = true;
        assertEquals(-1, rank);
        assertTrue(support < floor, "rank -1 but support " + support + " clears floor " + floor);
        assertTrue(f.path("below_support_floor").asBoolean(false), "missing below_support_floor");
      } else {
        assertFalse(seenUnranked, "ranked features must all precede the unranked ones");
        assertTrue(support >= floor, "ranked but support " + support + " below floor " + floor);
        assertEquals(expected++, rank, "ranks must be dense and in array order");
        assertTrue(f.path("discrimination").asDouble() <= prev + 1e-9, "ranking not sorted");
        prev = f.path("discrimination").asDouble();
      }
    }

    // Exactly one state is active per cell per position, so every cell's support sums to positions.
    for (int i = 0; i < perCellSupport.length; i++) {
      assertEquals(
          positions,
          perCellSupport[i],
          "cell (" + i / V3FeatureMiner.BOARD + "," + i % V3FeatureMiner.BOARD + ") support sum");
    }
  }

  /**
   * Row shape for the v3.1 ridge fit: exactly one feature per cell, so 144 in-range indices that
   * never collide on a cell. A row that violates this silently makes the design matrix wrong.
   */
  @Test
  public void testEmittedPositionRowShape() throws Exception {
    Board b = board12();
    b.setCell(3, 4, new Cell(1, CellKind.NORMAL));
    b.setCell(5, 6, new Cell(2, CellKind.BASE));

    JsonNode row =
        new ObjectMapper()
            .readTree(
                V3FeatureMiner.positionRow(
                    "00e60d2e-4a2d-41c6-993f-7fc8c71f35b1",
                    4,
                    -1234,
                    V3FeatureMiner.activeFeatures(b, 1)));

    assertEquals("00e60d2e-4a2d-41c6-993f-7fc8c71f35b1", row.path("game_id").asText());
    assertEquals(4, row.path("ply").asInt());
    assertEquals(-1234, row.path("eval").asInt());

    JsonNode active = row.path("active");
    int cells = V3FeatureMiner.BOARD * V3FeatureMiner.BOARD;
    assertEquals(cells, active.size(), "one active feature per cell");
    boolean[] cellSeen = new boolean[cells];
    for (JsonNode i : active) {
      int index = i.asInt();
      assertTrue(
          index >= 0 && index < cells * V3FeatureMiner.STATES, "index out of range: " + index);
      assertFalse(cellSeen[index / V3FeatureMiner.STATES], "two active states for one cell");
      cellSeen[index / V3FeatureMiner.STATES] = true;
    }

    // The two placed stones map to the STM-normalized state of their own cell.
    assertEquals(
        V3FeatureMiner.idx(3, 4, PatternContract.NORMAL_SELF), active.get(3 * 12 + 4).asInt());
    assertEquals(
        V3FeatureMiner.idx(5, 6, PatternContract.BASE_OPPONENT), active.get(5 * 12 + 6).asInt());
  }

  /** {@code --emit-positions} is a pure addition: the aggregate artifact must not move at all. */
  @Test
  public void testEmitPositionsDoesNotAlterAggregateStats() throws Exception {
    Path db = tempDir.resolve("games.db");
    String pgn =
        "[{\"player\":1,\"moves\":[{\"type\":\"place\",\"row\":0,\"col\":1}]},"
            + "{\"player\":2,\"moves\":[{\"type\":\"place\",\"row\":11,\"col\":10}]},"
            + "{\"player\":1,\"moves\":[{\"type\":\"place\",\"row\":0,\"col\":2}]}]";
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        Statement statement = connection.createStatement()) {
      // TEXT uuid keys, exactly like the real games.db — an INTEGER id here would hide the
      // getLong() coercion that used to collapse every uuid game into game_id 0.
      statement.execute(
          "CREATE TABLE games (id TEXT PRIMARY KEY, rows INTEGER, cols INTEGER, "
              + "termination TEXT, pgn_content TEXT)");
      for (String id : GAME_IDS) {
        statement.execute(
            "INSERT INTO games VALUES ('" + id + "', 12, 12, 'resign', '" + pgn + "')");
      }
    }

    Path plain = tempDir.resolve("plain.json");
    Path withRows = tempDir.resolve("with_rows.json");
    Path jsonl = tempDir.resolve("positions.jsonl");
    V3FeatureMiner.main(new String[] {db.toString(), plain.toString()});
    V3FeatureMiner.main(
        new String[] {db.toString(), withRows.toString(), "--emit-positions", jsonl.toString()});

    assertEquals(
        Files.readString(plain), Files.readString(withRows), "the flag must not move aggregates");

    List<String> rows = Files.readAllLines(jsonl);
    JsonNode meta = new ObjectMapper().readTree(plain.toFile()).path("meta");
    long positions = meta.path("positions").asLong();
    assertEquals(positions, rows.size(), "one row per accumulated position");
    // The eval column is the regression target of the whole capacity fit, and nothing else here
    // reads it: a sign flip or a different movesLeft/tempo frame than the one stats.add saw would
    // leave every other assertion green and produce an inverted warm start. Tie it to the
    // aggregate's own baseline sum, which is computed from the same evals.
    long evalSum = 0;
    for (String r : rows) {
      evalSum += new ObjectMapper().readTree(r).path("eval").asLong();
    }
    assertEquals(
        meta.path("baseline_mean_eval").asDouble() * positions,
        evalSum,
        1e-6,
        "emitted evals must be the evals the aggregate accumulated");
    // Both games are identical, so plies run 0..2 twice, once per game_id. The uuids must survive
    // verbatim: the ridge fit splits on them, so two games sharing an id would be one game to it.
    JsonNode last = new ObjectMapper().readTree(rows.get(rows.size() - 1));
    assertEquals(GAME_IDS[1], last.path("game_id").asText());
    assertEquals(2, last.path("ply").asInt());
    assertEquals(
        Set.of(GAME_IDS),
        rows.stream()
            .map(
                r -> {
                  try {
                    return new ObjectMapper().readTree(r).path("game_id").asText();
                  } catch (Exception e) {
                    throw new IllegalStateException(e);
                  }
                })
            .collect(Collectors.toSet()),
        "one distinct game_id per game");
  }

  @Test
  public void testEmptyStatsAndDefaultFloor() {
    V3FeatureMiner.Stats empty = new V3FeatureMiner.Stats();
    assertEquals(0, empty.positions());
    assertEquals(0.0, empty.baselineMeanEval(), 1e-9);
    assertTrue(empty.ranked(empty.defaultSupportFloor()).isEmpty());
    assertEquals(30, empty.defaultSupportFloor());

    // Floor default is ~1% of positions but never below 30.
    V3FeatureMiner.Stats many = new V3FeatureMiner.Stats();
    for (int i = 0; i < 5000; i++) {
      many.add(board12(), 1, 0);
    }
    assertEquals(50, many.defaultSupportFloor());
  }
}
