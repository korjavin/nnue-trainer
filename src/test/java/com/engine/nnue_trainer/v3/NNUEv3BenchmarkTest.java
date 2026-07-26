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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Task 5: NPS of the {@code LeafEval.NNUEV3} leaf against {@code HAND_TUNED}, on the real corpus
 * boards from the parity fixture, at the live 60k-node budget.
 *
 * <p>Opt-in: set {@code NNUEV3_BENCH=1} to run (it burns ~10s of wall clock and its numbers are
 * machine-specific, so it stays off the default test path). Results are recorded in {@code
 * docs/nnue-v3-runtime.md}.
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
    List<Board> boards = fixtureBoards();

    System.out.println("=== NNUE v3 leaf benchmark (full recompute, 144 features/eval) ===");
    evalThroughput(ev, boards.get(0));

    Run hand = searchAll(boards, null);
    Run v3 = searchAll(boards, ev);

    System.out.printf(
        "search %d boards @ %,d-node budget — hand-tuned: %,10.0f nps (%,d nodes / %d ms) | "
            + "v3: %,10.0f nps (%,d nodes / %d ms)%n",
        boards.size(), NODE_BUDGET, hand.nps(), hand.nodes, hand.ms, v3.nps(), v3.nodes, v3.ms);
    System.out.printf(
        "v3/hand-tuned NPS ratio: %.2fx — %,d-node budget takes %.0f ms with the v3 leaf%n",
        v3.nps() / hand.nps(), NODE_BUDGET, NODE_BUDGET / v3.nps() * 1000);

    assertTrue(v3.nodes > 0, "benchmark must actually search");
  }

  private static void evalThroughput(NNUEv3Evaluator ev, Board b) {
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
        "eval/s 12x12: %,12.0f  (%.4f ms/eval)  sink=%.1f%n",
        iters / (ns / 1e9), ns / 1e6 / iters, sink);
  }

  private record Run(long nodes, long ms) {
    double nps() {
      return nodes / Math.max(1e-3, ms / 1000.0);
    }
  }

  /** Total nodes and wall time over every board, with the v3 leaf when {@code ev != null}. */
  private static Run searchAll(List<Board> boards, NNUEv3Evaluator ev) {
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

  /** Real corpus positions — same boards the parity fixture asserts on. */
  private static List<Board> fixtureBoards() throws Exception {
    JsonNode doc = new ObjectMapper().readTree(FIXTURE.toFile());
    List<Board> out = new ArrayList<>();
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
      out.add(b);
    }
    return out;
  }
}
