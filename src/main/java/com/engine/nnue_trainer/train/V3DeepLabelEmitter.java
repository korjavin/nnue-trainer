package com.engine.nnue_trainer.train;

import com.engine.nnue_trainer.search.gobot.GoBotSearcher;
import com.engine.nnue_trainer.search.gobot.GoResult;
import com.engine.nnue_trainer.search.gobot.GoState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * bead d4a.6.4 — the DEEP-LABEL sibling dataset: same positions, groups, features, and JSONL schema
 * as {@link V3SiblingDatasetEmitter}, but each child is labelled with a fixed-depth {@link
 * GoBotSearcher} negamax value (hand-tuned leaf) instead of the static eval.
 *
 * <p>Rationale: distilling the static eval caps the net at hand-tuned strength, and six rounds of
 * offline metrics have shown the cap binds well below it in practice. A depth-D label bakes D plies
 * of search knowledge into the leaf, so the same runtime search queries a strictly deeper oracle.
 *
 * <p>The label is the search value from the CHILD's own {@code currentPlayer()} — the same frame
 * {@code leafEval} queries — and the {@code s} sign maps it to the parent frame exactly as in the
 * sibling emitter. The value is written into the {@code "ht"} key so {@code python.v3.train_net}
 * consumes it unchanged. Mate-ish scores are clamped to {@link #SCORE_CLAMP} to keep training
 * targets in eval-unit range.
 *
 * <p>CLI: {@code V3DeepLabelEmitter [db] [out] [depth] [shardIdx] [shardCount]}. Sharding splits by
 * game index so N processes can label disjoint game sets concurrently; concatenate the outputs.
 */
public final class V3DeepLabelEmitter {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Mate-scale search scores are certainty, not eval units; clamp into a finite band. */
  public static final long SCORE_CLAMP = 50_000L;

  private V3DeepLabelEmitter() {}

  /** Child-frame depth-D search value, clamped; falls back to null when search can't run. */
  static Long deepValue(GoState child, int depth) {
    GoResult r = GoBotSearcher.chooseDepth(child, depth);
    if (r == null) {
      return null;
    }
    return Math.max(-SCORE_CLAMP, Math.min(SCORE_CLAMP, (long) r.score));
  }

  public static void main(String[] args) throws Exception {
    Path db = Path.of(args.length > 0 ? args[0] : "games.db");
    Path out = Path.of(args.length > 1 ? args[1] : "/tmp/nnue_v3_deep.jsonl");
    int depth = args.length > 2 ? Integer.parseInt(args[2]) : 2;
    int shardIdx = args.length > 3 ? Integer.parseInt(args[3]) : 0;
    int shardCount = args.length > 4 ? Integer.parseInt(args[4]) : 1;
    if (!Files.exists(db)) {
      System.err.println("games.db not found: " + db);
      System.exit(1);
    }

    long posId = 0;
    long rows = 0;
    int gameIdx = 0;
    int gamesUsed = 0;
    long t0 = System.currentTimeMillis();
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
        // Shard AFTER all skip filters so every shard sees the same usable-game numbering, and
        // pos_id gets a shard-disjoint range (game index in the high bits).
        int myGame = gameIdx++;
        if (myGame % shardCount != shardIdx) {
          continue;
        }
        gamesUsed++;
        for (GamesDbReplay.Snapshot s : replay.snapshots) {
          GoState state = GoState.fromBoard(s.board, s.stm, GamesDbReplay.MOVES_LEFT, s.neutralUsed);
          List<com.engine.nnue_trainer.board.Action> legal = state.legalActions();
          if (legal.size() < V3SiblingDatasetEmitter.MIN_CHILDREN) {
            continue;
          }
          int mover = state.currentPlayer();
          List<String> lines = new ArrayList<>(legal.size());
          long pid = ((long) myGame << 20) | (posId & 0xFFFFF);
          for (com.engine.nnue_trainer.board.Action a : legal) {
            GoState child = state.apply(a);
            if (child == null) {
              continue;
            }
            Long dv = deepValue(child, depth);
            if (dv == null) {
              continue;
            }
            int cp = child.currentPlayer();
            V3SiblingDatasetEmitter.Child c =
                new V3SiblingDatasetEmitter.Child(
                    V3FeatureMiner.activeFeatures(child.toBoard(), cp),
                    (int) (long) dv,
                    cp == mover ? 1 : -1,
                    child.movesLeft());
            lines.add(V3SiblingDatasetEmitter.row(gameId, pid, c));
          }
          if (lines.size() < V3SiblingDatasetEmitter.MIN_CHILDREN) {
            continue;
          }
          for (String line : lines) {
            w.write(line);
            w.newLine();
            rows++;
          }
          posId++;
        }
      }
    }
    double secs = (System.currentTimeMillis() - t0) / 1000.0;
    System.out.printf(
        "shard %d/%d: games %d, groups %d, rows %d, depth %d, %.1fs (%.1f ms/row)%n",
        shardIdx, shardCount, gamesUsed, posId, rows, depth, secs, rows == 0 ? 0 : 1000.0 * secs / rows);
  }
}
