package com.engine.nnue_trainer.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.CellKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class NNUEv2EvaluatorTest {

  private String originalProperty;

  @BeforeEach
  public void setUp() {
    originalProperty = System.getProperty("USE_NNUE_V2");
  }

  @AfterEach
  public void tearDown() {
    if (originalProperty == null) {
      System.clearProperty("USE_NNUE_V2");
    } else {
      System.setProperty("USE_NNUE_V2", originalProperty);
    }
  }

  @Test
  public void testEvaluatorFallbackWhenFlagFalse() {
    System.setProperty("USE_NNUE_V2", "false");
    NNUEv2Evaluator evaluator = new NNUEv2Evaluator();

    Board board = new Board(5, 5);
    // Player 1 has 3 pieces, Player 2 has 1 piece
    board.getCell(0, 0).owner = 1; board.getCell(0, 0).kind = CellKind.BASE;
    board.getCell(0, 1).owner = 1; board.getCell(0, 1).kind = CellKind.NORMAL;
    board.getCell(0, 2).owner = 1; board.getCell(0, 2).kind = CellKind.NORMAL;

    board.getCell(4, 4).owner = 2; board.getCell(4, 4).kind = CellKind.BASE;

    float score = evaluator.evaluateBoard(board, 1, true);

    // Fallback static on 5x5 should be myPieces - oppPieces = 3 - 1 = 2
    assertEquals(2.0f, score, 0.001f);
  }

  @Test
  public void testEvaluatorUsesV2WhenFlagTrue() {
    System.setProperty("USE_NNUE_V2", "true");
    NNUEv2Evaluator evaluator = new NNUEv2Evaluator();

    Board board = new Board(5, 5);
    board.getCell(0, 0).owner = 1; board.getCell(0, 0).kind = CellKind.BASE;
    board.getCell(4, 4).owner = 2; board.getCell(4, 4).kind = CellKind.BASE;

    float scoreV2 = evaluator.evaluateBoard(board, 1, true);
    // Dummy V2 has all zero weights and bias, so it returns 0.0
    assertEquals(0.0f, scoreV2, 0.001f);

    System.setProperty("USE_NNUE_V2", "false");

    // Add one normal piece for player 1, fallback static should be 1.0
    board.getCell(0, 1).owner = 1; board.getCell(0, 1).kind = CellKind.NORMAL;

    float newScoreV1Fallback = evaluator.evaluateBoard(board, 1, true);
    assertEquals(1.0f, newScoreV1Fallback, 0.001f);
  }
}
