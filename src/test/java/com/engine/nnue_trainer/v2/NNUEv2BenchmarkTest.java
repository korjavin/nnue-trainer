package com.engine.nnue_trainer.v2;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.nnue.NNUEModel;
import com.engine.nnue_trainer.search.SearchEngine;
import com.engine.nnue_trainer.search.SearchResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Prototype throughput benchmark for the opt-in v2 evaluator. Gated on the 344MB weights blob
 * (regen: {@code python3 python/v2/export_weights.py}); skipped when absent so CI stays green.
 * Reports load time + heap footprint (honest: these are float prototype weights, quantization is
 * future work), eval/s per board size, and search NPS (v1 baseline vs v2) on 12x12.
 */
public class NNUEv2BenchmarkTest {

  private static final Path DICT = Path.of("python", "v2", "nnue_v2_dictionary.json");
  private static final Path WEIGHTS = Path.of("python", "v2", "nnue_v2_weights.json");

  @Test
  public void benchmark() throws Exception {
    assumeTrue(Files.exists(WEIGHTS), "344MB weights blob absent — skipping benchmark");

    Runtime rt = Runtime.getRuntime();
    gc();
    long usedBefore = rt.totalMemory() - rt.freeMemory();
    long t0 = System.nanoTime();
    NNUEv2Evaluator ev = NNUEv2Evaluator.load(WEIGHTS, DICT);
    long loadMs = (System.nanoTime() - t0) / 1_000_000;
    gc();
    long usedAfter = rt.totalMemory() - rt.freeMemory();
    long footprintMb = Math.max(0, usedAfter - usedBefore) / (1024 * 1024);

    System.out.println(
        "=== NNUE v2 evaluator benchmark (float prototype; quantization is future work) ===");
    System.out.printf(
        "load: %d ms, resident float weights ~%d MB (from a %.0f MB JSON blob)%n",
        loadMs, footprintMb, Files.size(WEIGHTS) / 1e6);

    evalThroughput(ev, midgame(12, 12), "12x12");
    evalThroughput(ev, midgame(8, 8), "8x8");
    evalThroughput(ev, midgame(16, 16), "16x16");

    searchNps(ev, 12, 12, 3);

    assertTrue(Float.isFinite(ev.evaluate(midgame(12, 12), 1)), "eval must be finite");
  }

  private static void evalThroughput(NNUEv2Evaluator ev, Board b, String label) {
    for (int i = 0; i < 300; i++) {
      ev.evaluate(b, 1);
    }
    int iters = 3000;
    long t0 = System.nanoTime();
    float sink = 0;
    for (int i = 0; i < iters; i++) {
      sink += ev.evaluate(b, 1 + (i & 1));
    }
    long ns = System.nanoTime() - t0;
    double evalsPerSec = iters / (ns / 1e9);
    System.out.printf(
        "eval/s %-6s: %,10.0f  (%.3f ms/eval)  sink=%.3f%n",
        label, evalsPerSec, ns / 1e6 / iters, sink);
  }

  private static void searchNps(NNUEv2Evaluator ev, int rows, int cols, int depth) {
    Board b = midgame(rows, cols);

    SearchEngine baseline = new SearchEngine(NNUEModel.createDefault());
    SearchResult rb = baseline.findBestActionUsingModel(b, 1, depth, false);
    double npsBase = rb.nodesEvaluated / Math.max(1e-3, rb.timeMs / 1000.0);

    SearchEngine v2 = new SearchEngine(NNUEModel.createDefault());
    v2.setUseNnueV2Eval(true);
    v2.setNnueV2Evaluator(ev);
    SearchResult rv = v2.findBestActionUsingModel(b, 1, depth, false);
    double npsV2 = rv.nodesEvaluated / Math.max(1e-3, rv.timeMs / 1000.0);

    System.out.printf(
        "search NPS %dx%d depth %d — v1-baseline: %,10.0f (%d nodes / %d ms) | "
            + "v2: %,10.0f (%d nodes / %d ms)%n",
        rows,
        cols,
        depth,
        npsBase,
        rb.nodesEvaluated,
        rb.timeMs,
        npsV2,
        rv.nodesEvaluated,
        rv.timeMs);
  }

  /** Deterministic pseudo-random mid-game position with both bases and mixed ownership. */
  private static Board midgame(int rows, int cols) {
    Board b = new Board(rows, cols);
    for (int r = 0; r < rows; r++) {
      for (int c = 0; c < cols; c++) {
        b.setCell(r, c, new Cell(0, CellKind.EMPTY));
      }
    }
    b.setCell(0, 0, new Cell(1, CellKind.BASE));
    b.setCell(rows - 1, cols - 1, new Cell(2, CellKind.BASE));
    Random rng = new Random(42L + rows * 31L + cols);
    int pieces = rows * cols / 4;
    for (int i = 0; i < pieces; i++) {
      int r = rng.nextInt(rows);
      int c = rng.nextInt(cols);
      if ((r == 0 && c == 0) || (r == rows - 1 && c == cols - 1)) {
        continue;
      }
      int owner = 1 + rng.nextInt(2);
      CellKind kind = rng.nextInt(4) == 0 ? CellKind.FORTIFIED : CellKind.NORMAL;
      b.setCell(r, c, new Cell(owner, kind));
    }
    return b;
  }

  private static void gc() {
    for (int i = 0; i < 3; i++) {
      System.gc();
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
