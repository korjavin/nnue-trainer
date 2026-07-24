package com.engine.nnue_trainer.train;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.nnue.NNUEModel;
import com.engine.nnue_trainer.search.SearchEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;

class SelfPlayGeneratorTest {

  private static Board startingBoard() {
    Board board = new Board(12, 12);
    board.setCell(0, 0, new Cell(1, CellKind.BASE));
    board.setCell(11, 11, new Cell(2, CellKind.BASE));
    return board;
  }

  private static SelfPlayGenerator.Config tdConfig(double lambda) {
    SelfPlayGenerator.Config config = new SelfPlayGenerator.Config();
    config.labelMode = SelfPlayGenerator.LabelMode.TD_LEAF;
    config.tdLambda = lambda;
    config.searchDepth = 2;
    return config;
  }

  @Test
  void tdLeafTargetEqualsSearchValueAtLambdaZero() {
    Board board = startingBoard();
    // Two fresh engines with the same warm-start weights are deterministic, so the standalone
    // search value must equal the TD-leaf(λ=0) target computed inside the generator.
    float searchValue =
        new SearchEngine(NNUEModel.createDefault())
            .findBestActionUsingModel(board, 1, 2, false)
            .score;
    float target =
        SelfPlayGenerator.computeTarget(
            new SearchEngine(NNUEModel.createDefault()), board, 1, false, 0, tdConfig(0.0));
    assertEquals(searchValue, target, 1e-4, "λ=0 target should equal the raw search value");
  }

  @Test
  void tdLeafTargetEqualsOutcomeAtLambdaOne() {
    Board board = startingBoard();
    SearchEngine engine = new SearchEngine(NNUEModel.createDefault());
    // Side to move (player 1) won → +1, lost → -1, regardless of the search value.
    assertEquals(
        1.0f, SelfPlayGenerator.computeTarget(engine, board, 1, false, 1, tdConfig(1.0)), 1e-6);
    assertEquals(
        -1.0f, SelfPlayGenerator.computeTarget(engine, board, 1, false, 2, tdConfig(1.0)), 1e-6);
  }

  @Test
  void tdLeafTargetHasSideToMoveSign() {
    Board board = startingBoard();
    SearchEngine engine = new SearchEngine(NNUEModel.createDefault());
    // Same winner (player 1), opposite side to move → opposite sign.
    float fromWinner = SelfPlayGenerator.computeTarget(engine, board, 1, false, 1, tdConfig(1.0));
    float fromLoser = SelfPlayGenerator.computeTarget(engine, board, 2, false, 1, tdConfig(1.0));
    assertEquals(1.0f, fromWinner, 1e-6);
    assertEquals(-1.0f, fromLoser, 1e-6);
  }

  @Test
  void testSingleGameGeneration() throws Exception {
    File tempDir = new File(System.getProperty("java.io.tmpdir"));
    File tempFile =
        new File(tempDir, "self_play_data_test_" + System.currentTimeMillis() + ".json");
    if (tempFile.exists()) {
      tempFile.delete();
    }

    SelfPlayGenerator.main(new String[] {"1", tempFile.getAbsolutePath()});

    assertTrue(tempFile.exists(), "Dataset JSON file should have been generated.");
    assertTrue(tempFile.length() > 0, "Dataset JSON file should not be empty.");

    // Clean up
    tempFile.delete();
  }

  @Test
  void testVariableBoardSizeGeneration() {
    // A 7x7 board (not the historical 12x12) plays through generate() with no 12x12 assumption.
    // The v1 864-dim one-hot mapper is 12x12-only, so the v1 dataset is empty off-12x12 by design
    // (the raw corpus path carries non-12x12 data); the point here is that generation runs clean.
    SelfPlayGenerator.Config config = new SelfPlayGenerator.Config();
    config.rows = 7;
    config.cols = 7;
    config.numGames = 1;
    config.maxTurns = 15;
    config.seed = 42;

    SelfPlayGenerator.GenerationResult result = SelfPlayGenerator.generate(config, null);

    assertTrue(result != null && result.dataset != null, "7x7 generation should complete.");
  }

  @Test
  void testRawEmitProducesValidJsonl() throws Exception {
    // Raw corpus on a non-12x12 board: each JSONL line must parse and satisfy the v2 schema.
    File tempDir = new File(System.getProperty("java.io.tmpdir"));
    File rawFile = new File(tempDir, "raw_corpus_test_" + System.currentTimeMillis() + ".jsonl");
    rawFile.delete();

    SelfPlayGenerator.Config config = new SelfPlayGenerator.Config();
    config.rows = 7;
    config.cols = 7;
    config.numGames = 2;
    config.maxTurns = 15;
    config.seed = 42;
    config.rawOutPath = rawFile.getAbsolutePath();

    SelfPlayGenerator.GenerationResult result = SelfPlayGenerator.generate(config, null);
    assertTrue(
        result.rawPositions != null && !result.rawPositions.isEmpty(),
        "Raw positions should be collected.");

    // Persist via the same compact JSONL writer main() uses, then validate line-by-line.
    ObjectMapper mapper = new ObjectMapper();
    try (java.io.BufferedWriter w = Files.newBufferedWriter(rawFile.toPath())) {
      for (SelfPlayGenerator.RawPosition p : result.rawPositions) {
        w.write(mapper.writeValueAsString(p));
        w.newLine();
      }
    }

    List<String> lines = Files.readAllLines(rawFile.toPath());
    assertTrue(lines.size() == result.rawPositions.size(), "One line per position.");
    for (String line : lines) {
      JsonNode node = mapper.readTree(line);
      int rows = node.get("rows").asInt();
      int cols = node.get("cols").asInt();
      assertEquals(7, rows, "rows");
      assertEquals(7, cols, "cols");
      assertTrue(node.has("stm"), "has stm");
      double wdl = node.get("wdl").asDouble();
      assertTrue(wdl == 0.0 || wdl == 0.5 || wdl == 1.0, "wdl in {0,0.5,1}, was " + wdl);

      JsonNode cells = node.get("cells");
      assertEquals(rows, cells.size(), "cells outer dim == rows");
      for (JsonNode row : cells) {
        assertEquals(cols, row.size(), "cells inner dim == cols");
        for (JsonNode cell : row) {
          String kind = cell.get("kind").asText();
          int owner = cell.get("owner").asInt();
          boolean noOwner = kind.equals("EMPTY") || kind.equals("NEUTRAL");
          assertEquals(noOwner, owner == -1, "owner==-1 iff EMPTY/NEUTRAL for kind " + kind);
        }
      }
    }
    rawFile.delete();
  }

  @Test
  void testDiverseDatasetGeneration() {
    SelfPlayGenerator.Config config = new SelfPlayGenerator.Config();
    config.numGames = 1;
    config.maxTurns = 15;
    config.epsilon = 1.0;
    config.exploreTurns = 15;

    SelfPlayGenerator.GenerationResult result = SelfPlayGenerator.generate(config, null);

    assertTrue(result.dataset.size() > 0, "Dataset should not be empty.");
    assertTrue(
        result.distinctGameRatio > 0.8,
        "Distinct game ratio should be greater than 0.8, was: " + result.distinctGameRatio);
  }
}
