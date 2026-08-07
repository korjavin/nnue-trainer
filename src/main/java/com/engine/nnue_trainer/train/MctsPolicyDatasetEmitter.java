package com.engine.nnue_trainer.train;

import com.engine.nnue_trainer.board.Action;
import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.MoveAction;
import com.engine.nnue_trainer.board.PlaceNeutralsAction;
import com.engine.nnue_trainer.board.Pos;
import com.engine.nnue_trainer.search.gobot.GoState;
import com.engine.nnue_trainer.v2.PatternContract;
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
 * Phase 1 policy dataset (plan 20260807-mcts-az-feasibility D4): one row per <b>recorded action</b>
 * — the supervised (state, chosen-action) pairs the policy prior trains on. Unlike the per-turn
 * {@link GamesDbReplay.Snapshot}s, the state is threaded through the turn so each action is paired
 * with the exact mid-turn position it was chosen in (real {@code movesLeft}, real neutral budget).
 *
 * <p>Frame discipline: features are mover-relative via {@link PatternContract#getSymbol} from the
 * position's <b>own</b> {@code currentPlayer()} — the same frame the MCTS prior is queried in at
 * node expansion, so training and runtime cannot diverge (the v3 lesson).
 *
 * <p>Row schema (JSONL): {@code {"g":gameId,"sym":[144 ints 0..7],"ml":1..3,"nuo":0|1,"nux":0|1,
 * "lm":[legal move-target cells],"oc":[owned normal cells; empty when a neutral pair is illegal],
 * "t":"m"|"p","a":cell | [i,j]}}. Cells are {@code row*12+col}; the legal action set is {@code lm}
 * moves plus all i&lt;j pairs of {@code oc}; {@code nuo}/{@code nux} are the mover's/opponent's
 * neutral-used flags.
 *
 * <p>CLI: {@code MctsPolicyDatasetEmitter [db-path] [out-path]}; db defaults to {@code
 * $NNUE_GAMES_DB} then {@code games.db}, output to {@code /tmp/mcts_policy_dataset.jsonl}. Reports
 * the neutral-pair support count the plan asks to measure.
 */
public final class MctsPolicyDatasetEmitter {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final int BOARD = 12;

  private MctsPolicyDatasetEmitter() {}

  /** One emitted row, exposed for tests. */
  static ObjectNode row(String gameId, GoState state, Action action) {
    int mover = state.currentPlayer();
    ObjectNode n = MAPPER.createObjectNode();
    n.put("g", gameId);
    ArrayNode sym = n.putArray("sym");
    Board board = state.toBoard();
    for (int r = 0; r < BOARD; r++) {
      for (int c = 0; c < BOARD; c++) {
        sym.add(PatternContract.getSymbol(board.getCell(r, c), mover));
      }
    }
    n.put("ml", state.movesLeft());
    n.put("nuo", state.neutralUsed(mover) ? 1 : 0);
    n.put("nux", state.neutralUsed(3 - mover) ? 1 : 0);

    List<Action> legal = state.legalActions();
    ArrayNode lm = n.putArray("lm");
    boolean pairsLegal = false;
    for (Action a : legal) {
      if (a instanceof MoveAction) {
        lm.add(cell(((MoveAction) a).target));
      } else {
        pairsLegal = true;
      }
    }
    ArrayNode oc = n.putArray("oc");
    if (pairsLegal) {
      // The pair action space is exactly all i<j combos of the mover's owned normal cells; the
      // trainer reconstructs it from this list rather than shipping C(owned,2) indices per row.
      for (int r = 0; r < BOARD; r++) {
        for (int c = 0; c < BOARD; c++) {
          Board b = board;
          if (b.getCell(r, c).owner == mover
              && b.getCell(r, c).kind == com.engine.nnue_trainer.board.CellKind.NORMAL) {
            oc.add(r * BOARD + c);
          }
        }
      }
    }

    if (action instanceof MoveAction) {
      n.put("t", "m");
      n.put("a", cell(((MoveAction) action).target));
    } else {
      PlaceNeutralsAction pn = (PlaceNeutralsAction) action;
      int i = cell(pn.pos1);
      int j = cell(pn.pos2);
      n.put("t", "p");
      ArrayNode a = n.putArray("a");
      a.add(Math.min(i, j));
      a.add(Math.max(i, j));
    }
    n.put("nlegal", legal.size());
    return n;
  }

  private static int cell(Pos pos) {
    return pos.row * BOARD + pos.col;
  }

  public static void main(String[] args) throws Exception {
    Path db = Path.of(System.getenv().getOrDefault("NNUE_GAMES_DB", "games.db"));
    Path out = Path.of("/tmp/mcts_policy_dataset.jsonl");
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

    long rows = 0;
    long neutralRows = 0;
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
        // Validate the whole game with the shared replay first (same skip semantics as every
        // games.db consumer), then re-walk it action by action.
        if (GamesDbReplay.replay(BOARD, BOARD, turns).skipReason != null) {
          continue;
        }
        gamesUsed++;
        List<ObjectNode> emitted = perActionRows(gameId, turns);
        for (ObjectNode n : emitted) {
          w.write(MAPPER.writeValueAsString(n));
          w.newLine();
          rows++;
          if ("p".equals(n.get("t").asText())) {
            neutralRows++;
          }
        }
      }
    }
    System.out.println("=== MCTS policy dataset (plan 20260807 Phase 1) ===");
    System.out.println("games used      : " + gamesUsed);
    System.out.println("rows (actions)  : " + rows);
    System.out.println("neutral-pair support: " + neutralRows + " rows");
    System.out.println("output          : " + out.toAbsolutePath());
  }

  /** Every (pre-action state, recorded action) pair of one validated game. */
  static List<ObjectNode> perActionRows(String gameId, JsonNode turns) {
    List<ObjectNode> out = new ArrayList<>();
    Board board = GamesDbReplay.initialBoard(BOARD, BOARD);
    boolean[] neutralUsed = new boolean[2];
    boolean over = false;
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
        // Forced positions (one legal action) carry no policy signal — skip the row, apply anyway.
        if (state.legalActions().size() > 1) {
          out.add(row(gameId, state, action));
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
}
