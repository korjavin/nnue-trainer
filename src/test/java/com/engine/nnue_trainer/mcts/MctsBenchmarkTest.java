package com.engine.nnue_trainer.mcts;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.search.gobot.GoState;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Plan 20260807 Phase 0 task 4 micro-benchmarks (opt-in, {@code MCTS_BENCH=1} — the NNUEV3_BENCH
 * pattern): sims/s with the hand-tuned leaf, and the Java conv-trunk policy forward in isolation.
 * Numbers are hardware-dependent — never asserted, only printed.
 */
class MctsBenchmarkTest {

  private static GoState midState() {
    Board board = new Board(12, 12);
    board.setCell(0, 0, new Cell(1, CellKind.BASE));
    board.setCell(11, 11, new Cell(2, CellKind.BASE));
    board.setCell(0, 1, new Cell(1, CellKind.NORMAL));
    board.setCell(1, 1, new Cell(1, CellKind.NORMAL));
    board.setCell(2, 2, new Cell(1, CellKind.NORMAL));
    board.setCell(10, 10, new Cell(2, CellKind.NORMAL));
    board.setCell(9, 9, new Cell(2, CellKind.NORMAL));
    return GoState.fromBoard(board, 1, 3, new boolean[2]);
  }

  @Test
  void benchmark() throws Exception {
    assumeTrue(System.getenv("MCTS_BENCH") != null, "set MCTS_BENCH=1 to run the benchmark");

    // 1. sims/s, hand-tuned leaf, uniform prior (the Phase 1 gauntlet configuration).
    GoState state = midState();
    MctsSearcher.Config cfg = new MctsSearcher.Config();
    new MctsSearcher(state, cfg).runSims(5000); // warmup
    MctsSearcher s = new MctsSearcher(state, cfg);
    long t0 = System.nanoTime();
    s.runSims(20_000);
    double secs = (System.nanoTime() - t0) / 1e9;
    System.out.printf("uniform-prior MCTS: %.0f sims/s%n", s.simsRun() / secs);

    // 2. conv policy forward in isolation (evals/s/core — the plan's >=500 bar).
    Path weights = Path.of("mcts_policy.json");
    assumeTrue(Files.exists(weights), "mcts_policy.json missing");
    PolicyNetPrior prior = PolicyNetPrior.load(weights);
    int[] sym = new int[144];
    Board b = state.toBoard();
    for (int r = 0; r < 12; r++) {
      for (int c = 0; c < 12; c++) {
        sym[r * 12 + c] = com.engine.nnue_trainer.v2.PatternContract.getSymbol(b.getCell(r, c), 1);
      }
    }
    for (int i = 0; i < 500; i++) {
      prior.forward(sym, 3, 0, 0); // warmup
    }
    t0 = System.nanoTime();
    int evals = 2000;
    double sink = 0;
    for (int i = 0; i < evals; i++) {
      sink += prior.forward(sym, 1 + (i % 3), 0, 0).move[0];
    }
    secs = (System.nanoTime() - t0) / 1e9;
    System.out.printf(
        "conv policy forward: %.0f evals/s (%.2f ms/eval, sink %.3f)%n",
        evals / secs, 1000 * secs / evals, sink);

    // 3. sims/s with the trained prior in the loop.
    MctsSearcher.Config netCfg = new MctsSearcher.Config();
    netCfg.prior = prior;
    new MctsSearcher(state, netCfg).runSims(1000); // warmup
    MctsSearcher ns = new MctsSearcher(state, netCfg);
    t0 = System.nanoTime();
    ns.runSims(2000);
    secs = (System.nanoTime() - t0) / 1e9;
    System.out.printf("net-prior MCTS: %.0f sims/s%n", ns.simsRun() / secs);
  }
}
