package com.engine.nnue_trainer.train;

import com.engine.nnue_trainer.board.Action;
import com.engine.nnue_trainer.search.eval.HandTunedEval;
import com.engine.nnue_trainer.search.gobot.GoState;
import com.engine.nnue_trainer.v3.NNUEv3Evaluator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * bead nnue-trainer-78a — why does v3 lose the gauntlet (7-17) despite held-out R² = 0.976?
 *
 * <p>R² is measured across the whole position distribution, which is dominated by wide, easy
 * differences. Search does not need that. Search needs to ORDER SIBLING MOVES — children of one
 * position, differing by a single action. This probe measures that directly: for real positions
 * from games.db, score every legal child with the hand-tuned static eval and with eval_v3, then
 * report how well the two ORDERINGS agree.
 *
 * <p>All children of a given state share the same {@code movesLeft}, so the comparison is not
 * confounded by the tempo term that v3 has no feature for.
 *
 * <p>Reported: Spearman rank correlation per position, top-1 agreement (does v3 pick hand-tuned's
 * best move), top-3 overlap, and the sibling-delta scale (how far apart the best and second-best
 * children actually are) against v3's own error scale. If the deltas are smaller than the model's
 * residual, the ordering is noise no matter how good the global fit looks.
 */
public final class V3OrderingProbe {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final int MOVES_LEFT = 3;

  private V3OrderingProbe() {}

  public static void main(String[] args) throws Exception {
    Path db = Path.of(args.length > 0 ? args[0] : "/home/iv/games.db");
    int maxPositions = args.length > 1 ? Integer.parseInt(args[1]) : 400;
    if (!Files.exists(db)) {
      System.err.println("games.db not found: " + db);
      System.exit(1);
    }
    NNUEv3Evaluator v3 = NNUEv3Evaluator.load(NNUEv3Evaluator.DEFAULT_WEIGHTS);

    List<Double> spearmans = new ArrayList<>();
    List<Double> gaps = new ArrayList<>(); // hand-tuned best-minus-second across siblings
    int top1 = 0;
    int top3 = 0;
    int scored = 0;
    int childTotal = 0;

    try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
        Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT rows, cols, termination, pgn_content FROM games "
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
        GamesDbReplay.Replay replay = GamesDbReplay.replay(12, 12, turns);
        if (replay.skipReason != null) {
          continue;
        }
        for (GamesDbReplay.Snapshot s : replay.snapshots) {
          GoState state = GoState.fromBoard(s.board, s.stm, MOVES_LEFT, s.neutralUsed);
          List<Action> legal = state.legalActions();
          if (legal.size() < 3) {
            continue; // nothing to order
          }
          int mover = state.currentPlayer();
          List<double[]> pairs = new ArrayList<>(); // {handTuned, v3}
          for (Action a : legal) {
            GoState child = state.apply(a);
            if (child == null) {
              continue;
            }
            // Score every child from the SAME frame (the mover's), so this measures ordering
            // rather than perspective bookkeeping.
            double ht =
                HandTunedEval.staticEval(child.toBoard(), mover, child.movesLeft(), s.neutralUsed);
            double vv = v3.evaluate(child.toBoard(), mover);
            pairs.add(new double[] {ht, vv});
          }
          if (pairs.size() < 3) {
            continue;
          }
          childTotal += pairs.size();
          spearmans.add(spearman(pairs));
          gaps.add(bestMinusSecond(pairs));
          if (argmax(pairs, 0) == argmax(pairs, 1)) {
            top1++;
          }
          if (top3Overlap(pairs)) {
            top3++;
          }
          scored++;
          if (scored >= maxPositions) {
            break outer;
          }
        }
      }
    }

    System.out.println("=== v3 sibling-move ORDERING probe (bead 78a) ===");
    System.out.printf(
        "positions scored      : %d (%d children, avg %.1f/position)%n",
        scored, childTotal, scored == 0 ? 0.0 : (double) childTotal / scored);
    System.out.printf("mean Spearman rho     : %.4f%n", mean(spearmans));
    System.out.printf("median Spearman rho   : %.4f%n", median(spearmans));
    System.out.printf(
        "rho <= 0 (no better than random ordering): %.1f%%%n",
        100.0 * count(spearmans, r -> r <= 0.0) / Math.max(1, spearmans.size()));
    System.out.printf(
        "top-1 agreement       : %.1f%%  (v3 picks hand-tuned's best move)%n",
        100.0 * top1 / Math.max(1, scored));
    System.out.printf(
        "top-3 overlap         : %.1f%%  (hand-tuned's best is in v3's top 3)%n",
        100.0 * top3 / Math.max(1, scored));
    System.out.println();
    System.out.println(
        "--- resolution: is the model accurate enough to see the gap it must judge?");
    System.out.printf("median hand-tuned gap (best - 2nd best sibling): %.1f%n", median(gaps));
    System.out.printf("v3 holdout MAE (from bead d4a.6.1)             : 1230.0%n");
    System.out.printf(
        "VERDICT: model error is %.1fx the gap it must resolve%n",
        1230.0 / Math.max(1e-9, median(gaps)));
  }

  private static double bestMinusSecond(List<double[]> pairs) {
    List<double[]> sorted = new ArrayList<>(pairs);
    sorted.sort(Comparator.comparingDouble((double[] p) -> -p[0]));
    return sorted.get(0)[0] - sorted.get(1)[0];
  }

  private static int argmax(List<double[]> pairs, int col) {
    int best = 0;
    for (int i = 1; i < pairs.size(); i++) {
      if (pairs.get(i)[col] > pairs.get(best)[col]) {
        best = i;
      }
    }
    return best;
  }

  private static boolean top3Overlap(List<double[]> pairs) {
    int htBest = argmax(pairs, 0);
    List<Integer> idx = new ArrayList<>();
    for (int i = 0; i < pairs.size(); i++) {
      idx.add(i);
    }
    idx.sort(Comparator.comparingDouble((Integer i) -> -pairs.get(i)[1]));
    return idx.subList(0, Math.min(3, idx.size())).contains(htBest);
  }

  /** Spearman = Pearson on ranks; ties averaged. */
  private static double spearman(List<double[]> pairs) {
    double[] r0 = ranks(pairs, 0);
    double[] r1 = ranks(pairs, 1);
    double m0 = 0;
    double m1 = 0;
    for (int i = 0; i < r0.length; i++) {
      m0 += r0[i];
      m1 += r1[i];
    }
    m0 /= r0.length;
    m1 /= r1.length;
    double num = 0;
    double d0 = 0;
    double d1 = 0;
    for (int i = 0; i < r0.length; i++) {
      num += (r0[i] - m0) * (r1[i] - m1);
      d0 += (r0[i] - m0) * (r0[i] - m0);
      d1 += (r1[i] - m1) * (r1[i] - m1);
    }
    double den = Math.sqrt(d0 * d1);
    return den == 0 ? 0 : num / den;
  }

  private static double[] ranks(List<double[]> pairs, int col) {
    int n = pairs.size();
    Integer[] idx = new Integer[n];
    for (int i = 0; i < n; i++) {
      idx[i] = i;
    }
    java.util.Arrays.sort(idx, Comparator.comparingDouble(i -> pairs.get(i)[col]));
    double[] r = new double[n];
    int i = 0;
    while (i < n) {
      int j = i;
      while (j + 1 < n && pairs.get(idx[j + 1])[col] == pairs.get(idx[i])[col]) {
        j++;
      }
      double avg = (i + j) / 2.0 + 1;
      for (int k = i; k <= j; k++) {
        r[idx[k]] = avg;
      }
      i = j + 1;
    }
    return r;
  }

  private static double mean(List<Double> xs) {
    double s = 0;
    for (double x : xs) {
      s += x;
    }
    return xs.isEmpty() ? 0 : s / xs.size();
  }

  private static double median(List<Double> xs) {
    if (xs.isEmpty()) {
      return 0;
    }
    List<Double> c = new ArrayList<>(xs);
    java.util.Collections.sort(c);
    return c.get(c.size() / 2);
  }

  private static long count(List<Double> xs, java.util.function.DoublePredicate p) {
    return xs.stream().mapToDouble(Double::doubleValue).filter(p).count();
  }
}
