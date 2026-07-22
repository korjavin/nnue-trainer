package com.engine.nnue_trainer.v2;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.search.SearchEngine;
import com.engine.nnue_trainer.search.SearchResult;

public class BenchmarkV2Throughput {

  public static void main(String[] args) {
    System.setProperty("USE_NNUE_V2", "true");
    System.out.println("Starting BenchmarkV2Throughput...");

    int[] sizes = {5, 8, 12};
    int evalCount = 1000;

    for (int size : sizes) {
      System.out.println("\n=== Benchmarking Board Size: " + size + "x" + size + " ===");
      Board board = new Board(size, size);
      // Setup some default pieces to make it non-empty
      board.getCell(0, 0).owner = 1;
      board.getCell(size - 1, size - 1).owner = 2;

      NNUEv2Evaluator evaluator = new NNUEv2Evaluator();

      // Benchmark Evaluation Throughput
      long startEval = System.nanoTime();
      for (int i = 0; i < evalCount; i++) {
        evaluator.evaluateBoard(board, 1, true);
      }
      long endEval = System.nanoTime();
      double evalTimeSec = (endEval - startEval) / 1e9;
      double evalsPerSec = evalCount / evalTimeSec;

      System.out.printf("Evaluations per second: %,.2f%n", evalsPerSec);

      // Benchmark Search Throughput
      // Find best action performs IDDFS search
      int depth = 4;
      long startSearch = System.currentTimeMillis();
      SearchResult result = evaluator.findBestActionUsingModel(board, 1, depth, false);
      long endSearch = System.currentTimeMillis();
      double searchTimeSec = (endSearch - startSearch) / 1000.0;

      // Calculate search NPS
      double searchNps = result.nodesEvaluated / Math.max(0.001, searchTimeSec);

      System.out.printf("Search Nodes per second: %,.2f (Evaluated %,d nodes in %.3fs)%n",
          searchNps, result.nodesEvaluated, searchTimeSec);
    }
  }
}
