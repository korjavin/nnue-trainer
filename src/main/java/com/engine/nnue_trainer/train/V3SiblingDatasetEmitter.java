package com.engine.nnue_trainer.train;

import com.engine.nnue_trainer.board.Action;
import com.engine.nnue_trainer.search.eval.HandTunedEval;
import com.engine.nnue_trainer.search.gobot.GoState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * bead nnue-trainer-1uz — emits the SIBLING-GROUP dataset the v3 net trains on.
 *
 * <p>The v3.1 linear fit reached held-out R² = 0.976 and still lost the strength gauntlet 7-17,
 * because R² scores the whole position distribution while search only ever needs to ORDER THE
 * CHILDREN OF ONE POSITION. So the training data must be shaped like that decision: one row per
 * legal child, grouped by parent.
 *
 * <p>Enumeration mirrors {@code V3OrderingProbe} exactly — replay 12x12 games via {@link
 * GamesDbReplay}, rebuild each snapshot as a {@link GoState} with {@link GamesDbReplay#MOVES_LEFT}
 * moves left, take every {@link GoState#legalActions()} child, and score it with {@link
 * HandTunedEval} from the PARENT'S mover frame. Same frame for every sibling: a per-child
 * perspective flip would make the group's ordering meaningless. Positions with fewer than {@link
 * #MIN_CHILDREN} children are skipped — there is no ordering to learn from one or two moves.
 *
 * <p>Output is JSONL, one child per line: {@code {"game_id":G,"pos_id":P,"active":[144
 * ids],"ht":S}}. {@code game_id} rides along because the holdout MUST be split by game (positions
 * inside a game share nearly all their features); {@code pos_id} is the sibling group key.
 *
 * <p>CLI: {@code V3SiblingDatasetEmitter [db-path] [out-path]}; db defaults to {@code
 * $NNUE_GAMES_DB} then {@code games.db}, output to {@code /tmp/nnue_v3_siblings.jsonl}.
 */
public final class V3SiblingDatasetEmitter {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Same threshold V3OrderingProbe uses: below it there is nothing to order. */
  public static final int MIN_CHILDREN = 3;

  private V3SiblingDatasetEmitter() {}

  /** One legal child: its active feature ids and its hand-tuned score, both in the parent frame. */
  public static final class Child {
    public final int[] active;
    public final int ht;

    Child(int[] active, int ht) {
      this.active = active;
      this.ht = ht;
    }
  }

  /**
   * Every legal child of a snapshot, scored and featurized from the parent mover's frame. Returns
   * an empty list when the position has fewer than {@link #MIN_CHILDREN} children.
   */
  public static List<Child> children(GamesDbReplay.Snapshot s) {
    GoState state = GoState.fromBoard(s.board, s.stm, GamesDbReplay.MOVES_LEFT, s.neutralUsed);
    List<Action> legal = state.legalActions();
    if (legal.size() < MIN_CHILDREN) {
      return List.of();
    }
    int mover = state.currentPlayer();
    List<Child> out = new ArrayList<>(legal.size());
    for (Action a : legal) {
      GoState child = state.apply(a);
      if (child == null) {
        continue;
      }
      int ht = HandTunedEval.staticEval(child.toBoard(), mover, child.movesLeft(), s.neutralUsed);
      out.add(new Child(V3FeatureMiner.activeFeatures(child.toBoard(), mover), ht));
    }
    return out.size() < MIN_CHILDREN ? List.of() : out;
  }

  static String row(String gameId, long posId, Child c) throws Exception {
    ObjectNode n = MAPPER.createObjectNode();
    n.put("game_id", gameId);
    n.put("pos_id", posId);
    ArrayNode a = n.putArray("active");
    for (int i : c.active) {
      a.add(i);
    }
    n.put("ht", c.ht);
    return MAPPER.writeValueAsString(n);
  }

  public static void main(String[] args) throws Exception {
    Path db = Path.of(System.getenv().getOrDefault("NNUE_GAMES_DB", "games.db"));
    Path out = Path.of("/tmp/nnue_v3_siblings.jsonl");
    if (args.length > 0) {
      db = Path.of(args[0]);
    }
    if (args.length > 1) {
      out = Path.of(args[1]);
    }
    if (!Files.exists(db)) {
      System.err.println("games.db not found: " + db);
      System.exit(1);
    }

    long posId = 0;
    long rows = 0;
    int gamesUsed = 0;
    try (BufferedWriter w = Files.newBufferedWriter(out);
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
        Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT id, termination, pgn_content FROM games "
                    + "WHERE rows=12 AND cols=12 ORDER BY id")) {
      while (rs.next()) {
        String gameId = rs.getString("id");
        String termination = rs.getString("termination");
        String pgn = rs.getString("pgn_content");
        if ("illegal_move".equals(termination) || "disconnect".equals(termination) || pgn == null) {
          continue;
        }
        JsonNode turns;
        try {
          turns = MAPPER.readTree(pgn);
        } catch (Exception e) {
          continue;
        }
        if (!turns.isArray() || turns.isEmpty()) {
          continue;
        }
        GamesDbReplay.Replay replay = GamesDbReplay.replay(12, 12, turns);
        if (replay.skipReason != null) {
          continue;
        }
        gamesUsed++;
        for (GamesDbReplay.Snapshot s : replay.snapshots) {
          List<Child> kids = children(s);
          if (kids.isEmpty()) {
            continue;
          }
          for (Child c : kids) {
            w.write(row(gameId, posId, c));
            w.newLine();
            rows++;
          }
          posId++;
        }
      }
    }
    System.out.println("=== v3 sibling-group dataset (bead 1uz) ===");
    System.out.println("games used     : " + gamesUsed);
    System.out.println("sibling groups : " + posId);
    System.out.println("rows           : " + rows);
    System.out.printf("avg children   : %.1f%n", posId == 0 ? 0.0 : (double) rows / posId);
    System.out.println("output         : " + out.toAbsolutePath());
  }
}
