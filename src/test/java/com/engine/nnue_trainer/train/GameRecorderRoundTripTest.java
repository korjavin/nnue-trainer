package com.engine.nnue_trainer.train;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.MoveAction;
import com.engine.nnue_trainer.board.Pos;
import com.engine.nnue_trainer.search.gobot.GoBotSearcher;
import com.engine.nnue_trainer.search.gobot.GoState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The round-trip gate for gauntlet game recording: a match played with {@code Config.recordDb} set
 * must produce games.db rows that (1) parse, (2) replay legally through {@link GamesDbReplay} to a
 * final state whose winner matches the stored {@code result} under the stored {@code termination}
 * semantics, and (3) yield sibling rows through the real {@link V3SiblingDatasetEmitter} pipeline
 * (its termination filter included). Plus: appending to an existing db, and two recorder instances
 * writing one db (the parallel-gauntlet usage WAL + busy_timeout is there for).
 */
class GameRecorderRoundTripTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @TempDir Path tmp;

  @AfterEach
  void resetDefault() {
    GoBotSearcher.configureDefaultLeafEval(GoBotSearcher.LeafEval.HAND_TUNED, null);
  }

  private GauntletMatch.Config recordingConfig(String db) {
    GauntletMatch.Config config = new GauntletMatch.Config();
    config.fixedDepth = 2; // cheap + bypasses the opening book (same as GauntletMatchTest)
    config.games = 2;
    config.maxTurns = 6;
    config.recordDb = db;
    return config;
  }

  private record Row(String p1, String p2, int result, String termination, String pgn) {}

  private static List<Row> rows(String db) throws Exception {
    List<Row> out = new ArrayList<>();
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT rows, cols, player1_name, player2_name, result, termination, pgn_content "
                    + "FROM games ORDER BY rowid")) {
      while (rs.next()) {
        assertEquals(12, rs.getInt("rows"));
        assertEquals(12, rs.getInt("cols"));
        out.add(
            new Row(
                rs.getString("player1_name"),
                rs.getString("player2_name"),
                rs.getInt("result"),
                rs.getString("termination"),
                rs.getString("pgn_content")));
      }
    }
    return out;
  }

  /** Thread the recorded turns through the replayer's own legality-checked turn transition. */
  private static GoState replayToFinalState(JsonNode turns) {
    Board board = GamesDbReplay.initialBoard(12, 12);
    boolean[] neutralUsed = new boolean[2];
    GoState state = null;
    for (JsonNode turn : turns) {
      state =
          GamesDbReplay.applyTurn(
              board, turn.get("player").asInt(), turn.get("moves"), neutralUsed);
      assertNotNull(state, "recorded turn must replay legally: " + turn);
      board = state.toBoard();
      for (int p = 1; p <= 2; p++) {
        neutralUsed[p - 1] = state.neutralUsed(p);
      }
    }
    return state;
  }

  @Test
  void recordedGamesRoundTripThroughReplayAndEmitter() throws Exception {
    String db = tmp.resolve("gauntlet_record.db").toString();
    GauntletMatch.Config config = recordingConfig(db);

    GauntletMatch.play(null, null, config);

    List<Row> rows = rows(db);
    assertEquals(config.games, rows.size(), "one row per finished game");

    long siblingRows = 0;
    for (Row row : rows) {
      assertEquals("gauntlet:ht:d2", row.p1(), "provenance name");
      assertEquals("gauntlet:ht:d2", row.p2(), "provenance name");

      JsonNode turns = MAPPER.readTree(row.pgn());
      assertTrue(turns.isArray() && turns.size() > 0, "pgn_content is a non-empty turn array");

      // The miners' entry point: every snapshot replays without a skip.
      GamesDbReplay.Replay replay = GamesDbReplay.replay(12, 12, turns);
      assertNull(replay.skipReason, "recorded game must replay cleanly");
      assertTrue(replay.snapshots.size() > 0, "positions were mined");

      // Same action sequence + same transition rules == same boards; the stored result must match
      // the winner the replayed final state produces under the stored termination's semantics.
      GoState fin = replayToFinalState(turns);
      if ("no_moves".equals(row.termination())) {
        assertTrue(fin.gameOver(), "no_moves rows are decided games");
        assertEquals(row.result(), fin.winner(), "stored result == replayed winner");
      } else {
        assertEquals("turn_cap", row.termination(), "only the two shipped termination values");
        assertEquals(row.result(), fin.outcomeWinner(), "stored result == territory outcome");
      }

      for (GamesDbReplay.Snapshot s : replay.snapshots) {
        siblingRows += V3SiblingDatasetEmitter.children(s).size();
      }
    }
    assertTrue(siblingRows > 0, "recorded games feed the sibling dataset");

    // And through the emitter's REAL pipeline (SELECT + termination filter + replay + children):
    // no_moves/turn_cap must pass the illegal_move/disconnect drop.
    Path out = tmp.resolve("siblings.jsonl");
    V3SiblingDatasetEmitter.main(new String[] {db, out.toString()});
    assertTrue(Files.readAllLines(out).size() > 0, "emitter produced sibling rows from the db");
  }

  @Test
  void appendsToExistingDbDeterministically() throws Exception {
    String db = tmp.resolve("append.db").toString();

    GauntletMatch.play(null, null, recordingConfig(db));
    List<Row> first = rows(db);
    GauntletMatch.play(null, null, recordingConfig(db));
    List<Row> both = rows(db);

    assertEquals(first.size() * 2, both.size(), "second run appends, never clobbers");
    for (int i = 0; i < first.size(); i++) {
      // Same seed, deterministic search: the second run replays the identical games — which also
      // pins pgn serialization as deterministic.
      assertEquals(first.get(i).pgn(), both.get(first.size() + i).pgn(), "reproducible pgn");
    }
  }

  @Test
  void twoRecorderInstancesShareOneDb() throws Exception {
    String db = tmp.resolve("shared.db").toString();
    List<GameRecorder.Turn> turn = new ArrayList<>();
    GameRecorder.Turn t = new GameRecorder.Turn(1);
    t.moves.add(new MoveAction(new Pos(0, 1)));
    turn.add(t);

    // Two open connections interleaving inserts — the WAL + busy_timeout contract parallel
    // gauntlet processes rely on, exercised with in-process connections.
    try (GameRecorder a = new GameRecorder(db);
        GameRecorder b = new GameRecorder(db)) {
      a.record("a", "a", 1, "no_moves", GameRecorder.now(), turn);
      b.record("b", "b", 2, "no_moves", GameRecorder.now(), turn);
      a.record("a", "a", 1, "turn_cap", GameRecorder.now(), turn);
    }

    assertEquals(3, rows(db).size(), "all writers' rows land");
  }
}
