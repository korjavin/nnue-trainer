package com.engine.nnue_trainer.search.gobot;

import com.engine.nnue_trainer.board.Action;
import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.search.eval.HandTunedEval;
import com.engine.nnue_trainer.v2.NNUEv2Evaluator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * bead d4a.4.5 sibling-variance diagnostic. Confirms the flat-eval mechanism: at real mid-game
 * positions, does the v2 net's output SEPARATE the legal sibling moves, or is it near-constant
 * (giving the search no gradient to order moves)? For each position we enumerate the legal moves,
 * evaluate each resulting child from the mover's perspective with BOTH the raw NNUEv2Evaluator
 * output and HAND_TUNED, and measure the within-position spread.
 *
 * <p>Reported per evaluator: mean within-position sibling std (raw units), the global std across all
 * child evals, and their ratio {@code sep = meanWithinStd/globalStd} — a UNITLESS measure of how
 * much of the eval's dynamic range is spent separating siblings (a flat eval spends ~none). Also the
 * mean per-position Pearson correlation of the v2 vs hand-tuned sibling orderings (does v2 point the
 * same way as a discriminating eval?). Run twice — once per weights blob — for the before/after
 * comparison.
 *
 * <p>Usage: {@code java -cp ... SiblingVarianceDump <corpus.jsonl> [numPositions]} with
 * {@code NNUEV2_WEIGHTS}/{@code NNUEV2_DICT} pointing at the blob+dict. Only 12x12 lines are used.
 */
public final class SiblingVarianceDump {

  private SiblingVarianceDump() {}

  public static void main(String[] args) throws Exception {
    if (args.length < 1) {
      System.err.println("usage: SiblingVarianceDump <corpus.jsonl> [numPositions]");
      System.exit(2);
    }
    Path corpus = Path.of(args[0]);
    int want = args.length > 1 ? Integer.parseInt(args[1]) : 30;

    Path w = Path.of(sysval("NNUEV2_WEIGHTS", NNUEv2Evaluator.DEFAULT_WEIGHTS.toString()));
    Path d = Path.of(sysval("NNUEV2_DICT", NNUEv2Evaluator.DEFAULT_DICT.toString()));
    System.out.printf("Loading v2 evaluator: weights=%s dict=%s%n", w, d);
    NNUEv2Evaluator v2 = NNUEv2Evaluator.load(w, d);

    ObjectMapper om = new ObjectMapper();
    List<double[]> vRows = new ArrayList<>(); // per-position v2 sibling evals
    List<double[]> hRows = new ArrayList<>(); // per-position hand sibling evals
    int used = 0;

    try (BufferedReader br = Files.newBufferedReader(corpus)) {
      String line;
      while ((line = br.readLine()) != null && used < want) {
        line = line.trim();
        if (line.isEmpty()) {
          continue;
        }
        JsonNode obj = om.readTree(line);
        if (obj.path("rows").asInt() != 12 || obj.path("cols").asInt() != 12) {
          continue;
        }
        int stm = obj.path("stm").asInt(1);
        Board board = boardFromLine(obj);
        GoState state = GoState.fromBoard(board, stm, GoState.ACTIONS_PER_TURN, new boolean[2]);
        List<Action> actions = state.legalActions();
        if (actions.size() < 2) {
          continue; // no siblings to separate
        }
        double[] vs = new double[actions.size()];
        double[] hs = new double[actions.size()];
        for (int i = 0; i < actions.size(); i++) {
          GoState child = state.apply(actions.get(i));
          Board cb = child.toBoard();
          // Evaluate each move from the ORIGINAL mover's perspective (higher == better for stm).
          vs[i] = v2.evaluate(cb, stm);
          hs[i] =
              HandTunedEval.staticEval(
                  cb, stm, child.currentPlayer(), child.movesLeft(), child.neutralUsed);
        }
        vRows.add(vs);
        hRows.add(hs);
        used++;
      }
    }

    if (used == 0) {
      System.out.println("no usable 12x12 positions with >=2 legal moves found");
      return;
    }

    double meanWithinV = meanWithinStd(vRows);
    double meanWithinH = meanWithinStd(hRows);
    double globalV = globalStd(vRows);
    double globalH = globalStd(hRows);
    double corr = meanPearson(vRows, hRows);
    long scale = GoBotSearcher.NNUEV2_SCALE;

    System.out.println();
    System.out.printf("positions=%d  mean-siblings=%.1f  NNUEV2_SCALE(S)=%d%n",
        used, avgLen(vRows), scale);
    System.out.println("evaluator   mean-within-sib-std   global-std   sep=within/global");
    System.out.println("---------   -------------------   ----------   -----------------");
    System.out.printf("NNUEV2      %19.6f   %10.6f   %.4f%n", meanWithinV, globalV,
        globalV > 0 ? meanWithinV / globalV : 0.0);
    System.out.printf("HAND_TUNED  %19.2f   %10.2f   %.4f%n", meanWithinH, globalH,
        globalH > 0 ? meanWithinH / globalH : 0.0);
    System.out.printf("%nv2 sibling spread in de-normalized score units (x S): %.1f  (hand: %.1f)%n",
        meanWithinV * scale, meanWithinH);
    System.out.printf("mean per-position Pearson corr(v2, hand) over sibling moves: %.4f%n", corr);
    System.out.println("(sep≈0 or low corr => flat eval / no move-ordering gradient)");
  }

  private static Board boardFromLine(JsonNode obj) {
    int rows = obj.path("rows").asInt();
    int cols = obj.path("cols").asInt();
    Board board = new Board(rows, cols);
    JsonNode cells = obj.path("cells");
    for (int r = 0; r < rows; r++) {
      JsonNode row = cells.get(r);
      for (int c = 0; c < cols; c++) {
        JsonNode cell = row.get(c);
        CellKind kind = CellKind.valueOf(cell.path("kind").asText());
        if (kind != CellKind.EMPTY) {
          board.setCell(r, c, new Cell(cell.path("owner").asInt(-1), kind));
        }
      }
    }
    return board;
  }

  private static double std(double[] a) {
    int n = a.length;
    if (n < 2) {
      return 0.0;
    }
    double mean = 0.0;
    for (double x : a) {
      mean += x;
    }
    mean /= n;
    double ss = 0.0;
    for (double x : a) {
      ss += (x - mean) * (x - mean);
    }
    return Math.sqrt(ss / n);
  }

  private static double meanWithinStd(List<double[]> rows) {
    double s = 0.0;
    for (double[] r : rows) {
      s += std(r);
    }
    return s / rows.size();
  }

  private static double globalStd(List<double[]> rows) {
    List<Double> all = new ArrayList<>();
    for (double[] r : rows) {
      for (double x : r) {
        all.add(x);
      }
    }
    double[] flat = new double[all.size()];
    for (int i = 0; i < flat.length; i++) {
      flat[i] = all.get(i);
    }
    return std(flat);
  }

  private static double meanPearson(List<double[]> vs, List<double[]> hs) {
    double sum = 0.0;
    int n = 0;
    for (int i = 0; i < vs.size(); i++) {
      double c = pearson(vs.get(i), hs.get(i));
      if (!Double.isNaN(c)) {
        sum += c;
        n++;
      }
    }
    return n == 0 ? Double.NaN : sum / n;
  }

  private static double pearson(double[] a, double[] b) {
    int n = a.length;
    double ma = 0.0;
    double mb = 0.0;
    for (int i = 0; i < n; i++) {
      ma += a[i];
      mb += b[i];
    }
    ma /= n;
    mb /= n;
    double num = 0.0;
    double da = 0.0;
    double db = 0.0;
    for (int i = 0; i < n; i++) {
      double xa = a[i] - ma;
      double xb = b[i] - mb;
      num += xa * xb;
      da += xa * xa;
      db += xb * xb;
    }
    if (da == 0.0 || db == 0.0) {
      return Double.NaN; // a flat vector has no defined correlation
    }
    return num / Math.sqrt(da * db);
  }

  private static double avgLen(List<double[]> rows) {
    double s = 0.0;
    for (double[] r : rows) {
      s += r.length;
    }
    return s / rows.size();
  }

  private static String sysval(String key, String fallback) {
    String v = System.getProperty(key, System.getenv(key));
    return v != null ? v : fallback;
  }
}
