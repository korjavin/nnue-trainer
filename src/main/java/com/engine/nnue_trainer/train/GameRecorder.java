package com.engine.nnue_trainer.train;

import com.engine.nnue_trainer.board.Action;
import com.engine.nnue_trainer.board.MoveAction;
import com.engine.nnue_trainer.board.PlaceNeutralsAction;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Appends locally played gauntlet games to a sqlite db in EXACTLY the games.db schema, so they
 * augment the training corpus instead of being discarded. {@code pgn_content} is serialized into
 * the same turn-list JSON {@link GamesDbReplay#replay} parses — one object per turn ({@code
 * {"player":N,"moves":[...]}}), a board action as {@code {"type":"place","row":r,"col":c}} (the
 * replayer treats place/attack/move identically, so one type suffices), a neutral pair as {@code
 * {"type":"neutral","cells":[{row,col},{row,col}]}}. Zeros are written explicitly; the replayer's
 * omitempty tolerance is for the Go server's writer, not a rule.
 *
 * <p>Termination values: {@code no_moves} for a game {@code GoState} decided (result = {@code
 * winner()}), {@code turn_cap} for a maxTurns-capped game (result = {@code outcomeWinner()}, the
 * territory-tiebreak rule every self-play labeler uses — see {@code SelfPlayGenerator}). Both pass
 * the emitters' filters, which only drop {@code illegal_move}/{@code disconnect}; the sibling
 * emitter labels per-position (HandTunedEval), not from {@code result}, so capped games cannot
 * mislabel it, and {@code GameImporter} — which DOES label from {@code result} — only ingests
 * {@code player1_name LIKE 'GoBot%'} rows, which gauntlet names never match.
 *
 * <p>Concurrency: WAL journal + a 10s busy timeout, so parallel gauntlet processes appending to one
 * db retry instead of failing. One insert per game, autocommit.
 */
public final class GameRecorder implements AutoCloseable {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final DateTimeFormatter SQLITE_DATETIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  /** One turn: the mover and the actions the game actually applied, in order. */
  public static final class Turn {
    public final int player;
    public final List<Action> moves = new ArrayList<>();

    public Turn(int player) {
      this.player = player;
    }
  }

  private final Connection conn;

  public GameRecorder(String dbPath) throws SQLException {
    conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
    try (Statement st = conn.createStatement()) {
      st.execute("PRAGMA busy_timeout=10000");
      st.execute("PRAGMA journal_mode=WAL");
      st.execute(
          "CREATE TABLE IF NOT EXISTS games (id TEXT PRIMARY KEY, started_at DATETIME, "
              + "ended_at DATETIME, rows INTEGER, cols INTEGER, player1_name TEXT, "
              + "player2_name TEXT, player3_name TEXT, player4_name TEXT, result INTEGER, "
              + "termination TEXT, pgn_content TEXT, rejected_attempt TEXT)");
    }
  }

  /**
   * Append one finished game. {@code startedAt} is the sqlite-style UTC timestamp from {@link
   * #now()}.
   */
  public void record(
      String player1,
      String player2,
      int result,
      String termination,
      String startedAt,
      List<Turn> turns)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "INSERT INTO games (id, started_at, ended_at, rows, cols, player1_name, "
                + "player2_name, result, termination, pgn_content) "
                + "VALUES (?,?,?,12,12,?,?,?,?,?)")) {
      ps.setString(1, UUID.randomUUID().toString());
      ps.setString(2, startedAt);
      ps.setString(3, now());
      ps.setString(4, player1);
      ps.setString(5, player2);
      ps.setInt(6, result);
      ps.setString(7, termination);
      ps.setString(8, pgnJson(turns));
      ps.executeUpdate();
    }
  }

  /** Current UTC time in the space-separated sqlite DATETIME form the corpus uses. */
  public static String now() {
    return LocalDateTime.now(ZoneOffset.UTC).format(SQLITE_DATETIME);
  }

  /** The {@code pgn_content} turn-list JSON, exactly as {@link GamesDbReplay} parses it. */
  static String pgnJson(List<Turn> turns) {
    ArrayNode root = MAPPER.createArrayNode();
    for (Turn turn : turns) {
      ObjectNode t = root.addObject();
      t.put("player", turn.player);
      ArrayNode moves = t.putArray("moves");
      for (Action a : turn.moves) {
        moves.add(moveNode(a));
      }
    }
    try {
      return MAPPER.writeValueAsString(root);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static ObjectNode moveNode(Action a) {
    ObjectNode n = MAPPER.createObjectNode();
    if (a instanceof MoveAction) {
      MoveAction m = (MoveAction) a;
      n.put("type", "place");
      n.put("row", m.target.row);
      n.put("col", m.target.col);
      return n;
    }
    if (a instanceof PlaceNeutralsAction) {
      PlaceNeutralsAction p = (PlaceNeutralsAction) a;
      n.put("type", "neutral");
      ArrayNode cells = n.putArray("cells");
      cells.addObject().put("row", p.pos1.row).put("col", p.pos1.col);
      cells.addObject().put("row", p.pos2.row).put("col", p.pos2.col);
      return n;
    }
    throw new IllegalArgumentException("unrecordable action: " + a);
  }

  @Override
  public void close() throws SQLException {
    conn.close();
  }
}
