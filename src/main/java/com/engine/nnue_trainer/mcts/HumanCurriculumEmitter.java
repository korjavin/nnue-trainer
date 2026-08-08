package com.engine.nnue_trainer.mcts;

import com.engine.nnue_trainer.board.Action;
import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.search.gobot.GoState;
import com.engine.nnue_trainer.train.GamesDbReplay;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Locale;

/**
 * Human-games curriculum for the MCTS RL loop: expert iteration on human-reached positions. Replays
 * prod {@code games.db} 12x12 games and, for EVERY multi-choice position, runs a deep MCTS with the
 * current champion artifact ({@code MCTS_PRIOR}, value head via {@code MCTS_VALUE=net}) and emits
 * one row in the EXACT {@link SelfPlayMcts} schema — same {@code row()} code, so the frame
 * conventions cannot diverge: {@code sym} is mover-relative, {@code pi} the legal flat ids, {@code
 * pv} the search's root visit counts, and {@code z} the REAL game outcome in the <b>absolute</b>
 * frame (+1 = player 1 won, from the {@code result} column; the trainer flips it into the mover
 * frame via {@code "mover"} — the v3 lesson).
 *
 * <p>CLI: {@code HumanCurriculumEmitter <games.db> <out.jsonl> <sims> [shardIdx shardCount seed]
 * [--human-only]}. Game filters mirror {@code MctsPolicyDatasetEmitter} (12x12, no {@code
 * illegal_move}/{@code disconnect}, parseable pgn, {@link GamesDbReplay}-validated) plus {@code
 * result} in 0..2 (3+ marks a multiplayer game). {@code --human-only} keeps only games where at
 * least one player name is not a known bot ({@link #isHuman}). Sharding is by accepted-game
 * ordinal, and each game's search seeds derive from {@code (seed, ordinal)} only — output is
 * deterministic per (seed, shard) regardless of shard count. Root noise is OFF (these are target
 * labels, not exploration), so the searches are fully deterministic.
 */
public final class HumanCurriculumEmitter {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final int BOARD = 12;

  private HumanCurriculumEmitter() {}

  public static void main(String[] args) throws Exception {
    List<String> pos = new ArrayList<>();
    boolean humanOnly = false;
    for (String a : args) {
      if ("--human-only".equals(a)) {
        humanOnly = true;
      } else {
        pos.add(a);
      }
    }
    if (pos.size() != 3 && pos.size() != 6) {
      System.err.println(
          "usage: HumanCurriculumEmitter <games.db> <out.jsonl> <sims> "
              + "[shardIdx shardCount seed] [--human-only]");
      System.exit(2);
    }
    Path db = Path.of(pos.get(0));
    Path out = Path.of(pos.get(1));
    int sims = Integer.parseInt(pos.get(2));
    int shardIdx = pos.size() == 6 ? Integer.parseInt(pos.get(3)) : 0;
    int shardCount = pos.size() == 6 ? Integer.parseInt(pos.get(4)) : 1;
    long seed = pos.size() == 6 ? Long.parseLong(pos.get(5)) : 11L;
    if (!Files.exists(db)) {
      System.err.println("games.db not found: " + db);
      System.exit(1);
    }

    MctsSearcher.Config template = SelfPlayMcts.envTemplate();

    long t0 = System.currentTimeMillis();
    long rows = 0;
    int gamesUsed = 0;
    int gamesEmitted = 0;
    try (BufferedWriter w = Files.newBufferedWriter(out);
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
        Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT id, termination, result, player1_name, player2_name, pgn_content "
                    + "FROM games WHERE rows=12 AND cols=12 ORDER BY id")) {
      while (rs.next()) {
        String gameId = rs.getString("id");
        String termination = rs.getString("termination");
        int result = rs.getInt("result");
        String pgn = rs.getString("pgn_content");
        if ("illegal_move".equals(termination) || "disconnect".equals(termination) || pgn == null) {
          continue;
        }
        if (result < 0 || result > 2) {
          continue; // 3+ = a multiplayer winner; the 1v1 replay would skip it anyway
        }
        if (humanOnly
            && !isHuman(rs.getString("player1_name"))
            && !isHuman(rs.getString("player2_name"))) {
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
        if (GamesDbReplay.replay(BOARD, BOARD, turns).skipReason != null) {
          continue;
        }
        // Shard by accepted-game ordinal AFTER all filters, and derive the game seed from the
        // global ordinal (the SelfPlayMcts pattern) — same rows whatever the shard count.
        int ordinal = gamesUsed++;
        if (ordinal % shardCount != shardIdx) {
          continue;
        }
        int z = result == 1 ? 1 : result == 2 ? -1 : 0;
        long gameSeed = SelfPlayMcts.mix64(seed ^ (0x9E3779B97F4A7C15L * (ordinal + 1)));
        for (ObjectNode n : emitGame(gameId, turns, z, gameSeed, sims, template)) {
          w.write(MAPPER.writeValueAsString(n));
          w.newLine();
          rows++;
        }
        gamesEmitted++;
      }
    }
    double secs = (System.currentTimeMillis() - t0) / 1000.0;
    System.out.printf(
        "shard %d/%d: games %d of %d eligible, rows %d, sims %d, human-only %s, "
            + "%.1fs (%.2f rows/s)%n",
        shardIdx,
        shardCount,
        gamesEmitted,
        gamesUsed,
        rows,
        sims,
        humanOnly,
        secs,
        secs == 0 ? 0 : rows / secs);
  }

  /**
   * All curriculum rows of one validated game: the {@code MctsPolicyDatasetEmitter.perActionRows}
   * walk (state threaded through each turn — real {@code movesLeft}, real neutral budget), but the
   * target at each multi-choice position is a fresh MCTS search, recorded via {@link
   * SelfPlayMcts#row} with the game's absolute-frame outcome as {@code z}.
   */
  static List<ObjectNode> emitGame(
      String gameId,
      JsonNode turns,
      int zAbs,
      long gameSeed,
      int sims,
      MctsSearcher.Config template) {
    List<ObjectNode> out = new ArrayList<>();
    Board board = GamesDbReplay.initialBoard(BOARD, BOARD);
    boolean[] neutralUsed = new boolean[2];
    boolean over = false;
    int posIdx = 0;
    for (JsonNode turn : turns) {
      if (over) {
        break;
      }
      JsonNode playerNode = GamesDbReplay.field(turn, "player");
      JsonNode moves = GamesDbReplay.field(turn, "moves");
      if (playerNode == null || moves == null || !moves.isArray()) {
        continue;
      }
      int player = playerNode.asInt();
      GoState state = GoState.fromBoard(board, player, GoState.ACTIONS_PER_TURN, neutralUsed);
      for (JsonNode mv : moves) {
        if (state.gameOver() || state.currentPlayer() != player) {
          break;
        }
        Action action = GamesDbReplay.parseAction(mv);
        posIdx++;
        // Forced positions carry no policy signal — skip the row, apply anyway (both emitters).
        if (state.legalActions().size() > 1) {
          MctsSearcher.Config cfg =
              SelfPlayMcts.copyWithSeed(template, SelfPlayMcts.mix64(gameSeed ^ posIdx));
          MctsSearcher s = new MctsSearcher(state, cfg);
          s.runSims(sims);
          ObjectNode n = SelfPlayMcts.row(gameId, state, s.root());
          n.put("z", zAbs);
          out.add(n);
        }
        GoState next = state.apply(action);
        if (next == null) {
          // The shared replay validated this game; a reject here would be a divergence bug.
          throw new IllegalStateException("validated game rejected mid-walk: " + gameId);
        }
        state = next;
      }
      board = state.toBoard();
      for (int p = 1; p <= 2; p++) {
        neutralUsed[p - 1] = state.neutralUsed(p);
      }
      over = state.gameOver();
    }
    return out;
  }

  /**
   * Conservative bot-name check: a player is human unless the name matches a known bot family by
   * case-insensitive prefix — {@code "NNUE Bot %"}/{@code "nNuE Bot %"}/{@code "nnue Bot %"},
   * {@code "Bot %"}, {@code "GoBot%"}. Adjective-animal guest names (DarkOtter76, ...) and anything
   * unrecognized stay human, so a new bot family over-includes rather than dropping humans.
   */
  static boolean isHuman(String name) {
    if (name == null || name.isBlank()) {
      return false; // an absent player is not a human opponent
    }
    String n = name.toLowerCase(Locale.ROOT);
    return !(n.startsWith("nnue bot ") || n.startsWith("bot ") || n.startsWith("gobot"));
  }
}
