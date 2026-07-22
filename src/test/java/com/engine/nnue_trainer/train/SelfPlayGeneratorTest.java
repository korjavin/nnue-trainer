package com.engine.nnue_trainer.train;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.nnue.NNUEModel;
import com.engine.nnue_trainer.search.SearchEngine;
import java.io.File;
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
