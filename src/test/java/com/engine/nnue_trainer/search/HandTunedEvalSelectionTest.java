package com.engine.nnue_trainer.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.search.eval.HandTunedEval;
import org.junit.jupiter.api.Test;

/** The EVAL=HANDTUNED flag must route leaf evaluation to the ported GoBot eval, not NNUE. */
public class HandTunedEvalSelectionTest {

  private static Board sampleBoard() {
    Board board = new Board(12, 12);
    board.setCell(0, 0, new Cell(1, CellKind.BASE));
    board.setCell(0, 1, new Cell(1, CellKind.NORMAL));
    board.setCell(1, 0, new Cell(1, CellKind.NORMAL));
    board.setCell(11, 11, new Cell(2, CellKind.BASE));
    board.setCell(11, 10, new Cell(2, CellKind.NORMAL));
    board.setCell(10, 11, new Cell(2, CellKind.NORMAL));
    return board;
  }

  @Test
  public void flagOffUsesNnueByDefault() {
    SearchEngine engine = new SearchEngine();
    assertFalse(
        engine.isUseHandTunedEval(), "hand-tuned eval must be off by default (no EVAL env)");
  }

  @Test
  public void flagOnRoutesToHandTunedEval() {
    Board board = sampleBoard();
    int movesLeft = 2;
    boolean[] neutralUsed = {false, false, false, false};

    SearchEngine engine = new SearchEngine();
    engine.setUseHandTunedEval(true);
    engine.setHandTunedState(movesLeft, neutralUsed);

    float got = engine.evaluate(board, 1);
    int expected = HandTunedEval.staticEval(board, 1, movesLeft, neutralUsed);
    assertEquals(
        (float) expected, got, 0.0f, "flag on must return the integer HandTunedEval score");

    // The NNUE path (flag off) must produce a different value for the same board.
    SearchEngine nnue = new SearchEngine();
    assertNotEquals(got, nnue.evaluate(board, 1), "NNUE and hand-tuned evals must differ");
  }
}
