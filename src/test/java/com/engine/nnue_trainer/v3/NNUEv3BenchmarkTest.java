package com.engine.nnue_trainer.v3;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.search.gobot.GoBotSearcher;
import com.engine.nnue_trainer.search.gobot.GoResult;
import com.engine.nnue_trainer.search.gobot.GoState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Task 5: NPS of the v3 leaves — linear {@link NNUEv3Evaluator} and hidden-layer {@link
 * NNUEv3NetEvaluator} — against {@code HAND_TUNED}, on the real corpus boards from the parity
 * fixture, at the live 60k-node budget.
 *
 * <p>Opt-in: set {@code NNUEV3_BENCH=1} to run (it burns ~30s of wall clock and its numbers are
 * machine-specific, so it stays off the default test path). Results are recorded in {@code
 * docs/nnue-v3-runtime.md} and {@code docs/nnue-v3-net-runtime.md}.
 *
 * <p>The net's weights come from {@code NNUEV3NET_WEIGHTS} (default {@code nnue_v3_net.json}); if
 * that file is absent the bench falls back to the committed synthetic net so the harness still
 * works — with a loud note, because hidden size drives the cost.
 */
public class NNUEv3BenchmarkTest {

  private static final Path FIXTURE =
      Path.of("src", "test", "resources", "v3", "eval_parity_fixture.json");

  /** The live per-move node budget the bot actually plays with. */
  private static final long NODE_BUDGET = 60_000;

  @Test
  public void benchmark() throws Exception {
    assumeTrue(System.getenv("NNUEV3_BENCH") != null, "set NNUEV3_BENCH=1 to run the benchmark");

    NNUEv3Evaluator ev = NNUEv3Evaluator.load(NNUEv3Evaluator.DEFAULT_WEIGHTS);
    NNUEv3NetEvaluator net = loadNet();
    List<Board> boards = fixtureBoards();

    System.out.println("=== NNUE v3 leaf benchmark (full recompute, 144 features/eval) ===");
    evalThroughput("v3-linear", ev, boards.get(0));
    evalThroughput("v3-net H=" + net.hidden(), net, boards.get(0));

    Run hand = searchAll(boards, null);
    Run v3 = searchAll(boards, ev);
    Run v3net = searchAll(boards, net);

    System.out.printf(
        "search %d boards @ %,d-node budget%n  hand-tuned: %,10.0f nps (%,d nodes / %d ms)%n"
            + "  v3-linear : %,10.0f nps (%,d nodes / %d ms)%n"
            + "  v3-net    : %,10.0f nps (%,d nodes / %d ms)%n",
        boards.size(),
        NODE_BUDGET,
        hand.nps(),
        hand.nodes,
        hand.ms,
        v3.nps(),
        v3.nodes,
        v3.ms,
        v3net.nps(),
        v3net.nodes,
        v3net.ms);
    System.out.printf(
        "ratios vs hand-tuned: linear %.2fx, net %.2fx (net/linear %.2fx) — %,d nodes takes"
            + " %.0f ms with the net leaf%n",
        v3.nps() / hand.nps(),
        v3net.nps() / hand.nps(),
        v3net.nps() / v3.nps(),
        NODE_BUDGET,
        NODE_BUDGET / v3net.nps() * 1000);

    assertTrue(v3net.nodes > 0, "benchmark must actually search");
  }

  /** Real net weights when present, else the committed synthetic stub (hidden size differs!). */
  private static NNUEv3NetEvaluator loadNet() throws Exception {
    Path real =
        Path.of(V3Eval.sysval("NNUEV3NET_WEIGHTS", NNUEv3NetEvaluator.DEFAULT_WEIGHTS.toString()));
    if (Files.exists(real)) {
      return NNUEv3NetEvaluator.load(real);
    }
    Path synth = Path.of("src", "test", "resources", "v3", "net_synth_weights.json");
    System.out.printf("NOTE: %s absent — benching the SYNTHETIC net %s instead%n", real, synth);
    return NNUEv3NetEvaluator.load(synth);
  }

  private static void evalThroughput(String label, V3Eval ev, Board b) {
    for (int i = 0; i < 2000; i++) {
      ev.evaluate(b, 1);
    }
    int iters = 200_000;
    long t0 = System.nanoTime();
    double sink = 0;
    for (int i = 0; i < iters; i++) {
      sink += ev.evaluate(b, 1 + (i & 1));
    }
    long ns = System.nanoTime() - t0;
    System.out.printf(
        "%-14s eval/s 12x12: %,12.0f  (%.4f ms/eval)  sink=%.1f%n",
        label, iters / (ns / 1e9), ns / 1e6 / iters, sink);
  }

  private record Run(long nodes, long ms) {
    double nps() {
      return nodes / Math.max(1e-3, ms / 1000.0);
    }
  }

  /** Total nodes and wall time over every board, with the v3 leaf when {@code ev != null}. */
  private static Run searchAll(List<Board> boards, V3Eval ev) {
    GoBotSearcher.LeafConfig prev =
        ev == null
            ? GoBotSearcher.configureDefaultLeafEval(GoBotSearcher.LeafEval.HAND_TUNED, null)
            : GoBotSearcher.configureDefaultLeafEvalV3(GoBotSearcher.LeafEval.NNUEV3, ev);
    try {
      long nodes = 0;
      long t0 = System.nanoTime();
      for (Board b : boards) {
        GoResult r =
            GoBotSearcher.chooseNodeBudget(
                GoState.fromBoard(b, 1, 40, new boolean[4]), NODE_BUDGET);
        nodes += r == null ? 0 : r.nodes;
      }
      return new Run(nodes, (System.nanoTime() - t0) / 1_000_000);
    } finally {
      GoBotSearcher.restoreDefaultLeafEval(prev);
    }
  }

  /**
   * Real corpus positions — the parity-fixture boards that are searchable. The fixture is
   * STM-normalized, so a position mined with player 2 to move comes back with the bases swapped
   * (player 1's base at (11,11)); {@code HandTunedEval.isActive} tests the FIXED corners, so on
   * those boards both players read as base-less, the search terminates immediately and neither leaf
   * is exercised. They are skipped rather than silently contributing 0 nodes to the NPS numbers.
   */
  private static List<Board> fixtureBoards() throws Exception {
    JsonNode doc = new ObjectMapper().readTree(FIXTURE.toFile());
    List<Board> out = new ArrayList<>();
    int skipped = 0;
    for (JsonNode fx : doc.get("fixtures")) {
      if (fx.get("stm").asInt() != 1) {
        continue; // each board appears twice (stm=1, stm=2); search from player 1 only
      }
      Board b = new Board(NNUEv3Accumulator.BOARD, NNUEv3Accumulator.BOARD);
      for (JsonNode cell : fx.get("cells")) {
        b.setCell(
            cell.get("r").asInt(),
            cell.get("c").asInt(),
            new Cell(cell.get("owner").asInt(), CellKind.valueOf(cell.get("kind").asText())));
      }
      if (baseAt(b, 0, 0) == 1 && baseAt(b, 11, 11) == 2) {
        out.add(b);
      } else {
        skipped++;
      }
    }
    System.out.printf(
        "boards: %d searchable, %d skipped (bases not on the fixed corners)%n",
        out.size(), skipped);
    return out;
  }

  /** Owner of the base at {@code (r,c)}, or 0 if that cell is not a base. */
  private static int baseAt(Board b, int r, int c) {
    Cell cell = b.getCell(r, c);
    return cell != null && cell.kind == CellKind.BASE ? cell.owner : 0;
  }
}
