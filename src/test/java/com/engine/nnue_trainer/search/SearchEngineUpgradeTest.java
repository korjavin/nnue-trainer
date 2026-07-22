package com.engine.nnue_trainer.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import org.junit.jupiter.api.Test;

public class SearchEngineUpgradeTest {

  @Test
  public void testTTStability() {
    Board board = new Board(5, 5);
    board.setCell(1, 1, new Cell(1, CellKind.BASE));
    board.setCell(3, 3, new Cell(2, CellKind.BASE));

    SearchEngine engine = new SearchEngine();
    engine.clearSearchState(); // reset for first run

    // First run (empty TT)
    long startTime1 = System.currentTimeMillis();
    float eval1 =
        engine.alphaBeta(
            board,
            null,
            3,
            Float.NEGATIVE_INFINITY,
            Float.POSITIVE_INFINITY,
            1,
            1,
            startTime1,
            Long.MAX_VALUE);

    // Second run (populated TT)
    long startTime2 = System.currentTimeMillis();
    float eval2 =
        engine.alphaBeta(
            board,
            null,
            3,
            Float.NEGATIVE_INFINITY,
            Float.POSITIVE_INFINITY,
            1,
            1,
            startTime2,
            Long.MAX_VALUE);

    assertEquals(eval1, eval2, 0.001f, "Evaluation should be stable with TT.");
  }

  @Test
  public void testFindBestActionIsNotNullAndRuns() {
    Board board = new Board(12, 12);
    board.setCell(0, 0, new Cell(1, CellKind.BASE));
    board.setCell(11, 11, new Cell(2, CellKind.BASE));

    SearchResult result = SearchEngine.findBestAction(board, 1, 2, true); // Keep depth small
    assertNotNull(result.bestAction, "Best action should not be null");
  }

  @Test
  public void testOpeningBookTrigger() {
    Board board = new Board(5, 5);
    board.setCell(0, 0, new Cell(1, CellKind.BASE));
    board.setCell(4, 4, new Cell(2, CellKind.BASE));

    // Total pieces = 2 (< 6), so opening book should trigger and return 0.0 value and depth 1 fast
    SearchResult result = SearchEngine.findBestAction(board, 1, 4, true);
    assertEquals(0.0f, result.score, 0.001f, "Opening book should return 0 score");
    assertEquals(1, result.depth, "Opening book should return depth 1");
  }

  @Test
  public void testFindBestActionWithTimeLimitRuns() {
    Board board = new Board(12, 12);
    board.setCell(0, 0, new Cell(1, CellKind.BASE));
    board.setCell(11, 11, new Cell(2, CellKind.BASE));
    // Add pieces to bypass opening book
    for (int i = 0; i < 6; i++) {
      board.setCell(0, i + 1, new Cell(1, CellKind.NORMAL));
    }
    // Set time limit to an artificially very high bound to ensure it hits depth 1
    SearchResult result = SearchEngine.findBestActionWithTimeLimit(board, 1, 5000, true);
    assertNotNull(result.bestAction);
    assertTrue(result.depth >= 1, "Should reach at least depth 1. Reached: " + result.depth);
  }
}
