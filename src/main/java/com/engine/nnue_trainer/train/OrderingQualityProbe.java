package com.engine.nnue_trainer.train;

import com.engine.nnue_trainer.board.Action;
import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.MoveAction;
import com.engine.nnue_trainer.mcts.PolicyNetPrior;
import com.engine.nnue_trainer.search.gobot.GoBotSearcher;
import com.engine.nnue_trainer.search.gobot.GoResult;
import com.engine.nnue_trainer.search.gobot.GoState;
import com.engine.nnue_trainer.search.ordering.PolicyOrderingTable;
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
import java.util.Locale;

/**
 * Decides whether wiring {@link PolicyOrderingTable} into the searcher is worth it (epic
 * nnue-trainer-1jh): on real games.db positions, where does the move the hand-tuned depth-2 search
 * actually picks land when legal moves are ranked by (a) the table, (b) random, (c) the full conv
 * policy net? The reference comes from {@link GoBotSearcher#chooseDepth} — called READ-ONLY, the
 * searcher itself is untouched.
 *
 * <p>Reported per ranker: mean rank of the search-picked move, top-1/top-3 hit rate, and the
 * estimated cutoff-node savings {@code 1 - meanRank/meanRandomRank} (a beta cutoff at the best move
 * searches {@code rank} children instead of the random-order expectation {@code (n+1)/2}). Plus the
 * table's µs/position — the number that has to stay in the microsecond range for
 * every-interior-node use.
 *
 * <p>CLI: {@code OrderingQualityProbe [db] [maxPositions] [tableJson] [convJson]}; db defaults to
 * {@code $NNUE_GAMES_DB} then {@code games.db}.
 */
public final class OrderingQualityProbe {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final int BOARD = 12;
  private static final int REF_DEPTH = 2;

  private OrderingQualityProbe() {}

  /** Aggregates over the probed positions; see {@link #run}. */
  public static final class Result {
    public int positions;
    public double meanLegalMoves;
    public double meanRankTable;
    public double meanRankConv;
    public double meanRankRandom; // expected rank under a uniform shuffle: (n+1)/2
    public double top1Table;
    public double top3Table;
    public double top1Conv;
    public double top3Conv;
    public double nanosPerScore; // table.score() cost, steady state
  }

  /** Probes up to {@code maxPositions} snapshots; pass {@code conv = null} to skip that ranker. */
  public static Result run(
      Path db, int maxPositions, PolicyOrderingTable table, PolicyNetPrior conv) throws Exception {
    long rankTableSum = 0;
    long rankConvSum = 0;
    double rankRandomSum = 0;
    long legalSum = 0;
    int top1Table = 0;
    int top3Table = 0;
    int top1Conv = 0;
    int top3Conv = 0;
    int scored = 0;
    List<Board> benchBoards = new ArrayList<>();
    List<int[]> benchStmMl = new ArrayList<>();

    try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
        Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT termination, pgn_content FROM games "
                    + "WHERE rows=12 AND cols=12 ORDER BY id")) {
      outer:
      while (rs.next()) {
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
        GamesDbReplay.Replay replay = GamesDbReplay.replay(BOARD, BOARD, turns);
        if (replay.skipReason != null) {
          continue;
        }
        for (GamesDbReplay.Snapshot s : replay.snapshots) {
          GoState state =
              GoState.fromBoard(s.board, s.stm, GamesDbReplay.MOVES_LEFT, s.neutralUsed);
          List<Action> legal = state.legalActions();
          List<MoveAction> moves = new ArrayList<>();
          for (Action a : legal) {
            if (a instanceof MoveAction) {
              moves.add((MoveAction) a);
            }
          }
          if (moves.size() < 3) {
            continue; // nothing to order
          }
          GoResult ref = GoBotSearcher.chooseDepth(state, REF_DEPTH);
          if (!(ref.action instanceof MoveAction)) {
            continue; // the search picked a neutral pair — the table only orders placements
          }
          int refCell = cell((MoveAction) ref.action);

          double[] sc = table.score(s.board, s.stm, GamesDbReplay.MOVES_LEFT);
          int rankTable = 1;
          for (MoveAction m : moves) {
            if (sc[cell(m)] > sc[refCell]) {
              rankTable++;
            }
          }

          int rankConv = 0;
          if (conv != null) {
            float[] p = conv.priors(state, legal);
            double refPrior = -1;
            for (int i = 0; i < legal.size(); i++) {
              if (legal.get(i) instanceof MoveAction
                  && cell((MoveAction) legal.get(i)) == refCell) {
                refPrior = p[i];
                break;
              }
            }
            rankConv = 1;
            for (int i = 0; i < legal.size(); i++) {
              if (legal.get(i) instanceof MoveAction && p[i] > refPrior) {
                rankConv++;
              }
            }
          }

          rankTableSum += rankTable;
          rankConvSum += rankConv;
          rankRandomSum += (moves.size() + 1) / 2.0;
          legalSum += moves.size();
          top1Table += rankTable == 1 ? 1 : 0;
          top3Table += rankTable <= 3 ? 1 : 0;
          top1Conv += rankConv == 1 ? 1 : 0;
          top3Conv += rankConv > 0 && rankConv <= 3 ? 1 : 0;
          benchBoards.add(s.board);
          benchStmMl.add(new int[] {s.stm, GamesDbReplay.MOVES_LEFT});
          scored++;
          if (scored >= maxPositions) {
            break outer;
          }
        }
      }
    }

    Result r = new Result();
    r.positions = scored;
    if (scored == 0) {
      return r;
    }
    r.meanLegalMoves = (double) legalSum / scored;
    r.meanRankTable = (double) rankTableSum / scored;
    r.meanRankConv = conv == null ? Double.NaN : (double) rankConvSum / scored;
    r.meanRankRandom = rankRandomSum / scored;
    r.top1Table = (double) top1Table / scored;
    r.top3Table = (double) top3Table / scored;
    r.top1Conv = conv == null ? Double.NaN : (double) top1Conv / scored;
    r.top3Conv = conv == null ? Double.NaN : (double) top3Conv / scored;
    r.nanosPerScore = benchScore(table, benchBoards, benchStmMl);
    return r;
  }

  /** Steady-state cost of one full 144-cell scoring pass, in nanoseconds. */
  private static double benchScore(PolicyOrderingTable table, List<Board> boards, List<int[]> sm) {
    double sink = 0;
    for (int warm = 0; warm < 3; warm++) { // JIT warmup
      for (int i = 0; i < boards.size(); i++) {
        sink += table.score(boards.get(i), sm.get(i)[0], sm.get(i)[1])[0];
      }
    }
    int reps = Math.max(1, 2000 / boards.size());
    long t0 = System.nanoTime();
    for (int rep = 0; rep < reps; rep++) {
      for (int i = 0; i < boards.size(); i++) {
        sink += table.score(boards.get(i), sm.get(i)[0], sm.get(i)[1])[0];
      }
    }
    long elapsed = System.nanoTime() - t0;
    if (Double.isNaN(sink)) {
      System.out.println(sink); // keep the accumulator observable so the JIT can't drop the loop
    }
    return (double) elapsed / (reps * (long) boards.size());
  }

  private static int cell(MoveAction a) {
    return a.target.row * BOARD + a.target.col;
  }

  public static void main(String[] args) throws Exception {
    Path db =
        Path.of(
            args.length > 0 ? args[0] : System.getenv().getOrDefault("NNUE_GAMES_DB", "games.db"));
    int maxPositions = args.length > 1 ? Integer.parseInt(args[1]) : 400;
    Path tablePath = Path.of(args.length > 2 ? args[2] : "ordering_policy.json");
    Path convPath = Path.of(args.length > 3 ? args[3] : "mcts_policy.json");
    if (!Files.exists(db)) {
      System.err.println("games.db not found: " + db);
      System.exit(1);
    }
    PolicyOrderingTable table = PolicyOrderingTable.load(tablePath);
    PolicyNetPrior conv = Files.exists(convPath) ? PolicyNetPrior.load(convPath) : null;

    Result r = run(db, maxPositions, table, conv);
    System.out.println("=== ordering quality probe (epic 1jh) ===");
    System.out.printf(
        Locale.ROOT,
        "positions: %d (mean %.1f legal moves), reference: GoBotSearcher.chooseDepth(%d)%n",
        r.positions,
        r.meanLegalMoves,
        REF_DEPTH);
    System.out.printf(
        Locale.ROOT,
        "table : mean rank %.2f, top-1 %.1f%%, top-3 %.1f%%, est. cutoff-node savings %.1f%%%n",
        r.meanRankTable,
        100 * r.top1Table,
        100 * r.top3Table,
        100 * (1 - r.meanRankTable / r.meanRankRandom));
    if (conv != null) {
      System.out.printf(
          Locale.ROOT,
          "conv  : mean rank %.2f, top-1 %.1f%%, top-3 %.1f%%, est. cutoff-node savings %.1f%%%n",
          r.meanRankConv,
          100 * r.top1Conv,
          100 * r.top3Conv,
          100 * (1 - r.meanRankConv / r.meanRankRandom));
    }
    System.out.printf(Locale.ROOT, "random: mean rank %.2f (expected (n+1)/2)%n", r.meanRankRandom);
    System.out.printf(
        Locale.ROOT,
        "table scoring cost: %.2f µs per position (all 144 cells)%n",
        r.nanosPerScore / 1000.0);
  }
}
