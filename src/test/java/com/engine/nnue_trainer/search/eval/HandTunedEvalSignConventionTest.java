package com.engine.nnue_trainer.search.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import org.junit.jupiter.api.Test;

/**
 * Pins the STM-relative sign convention the v3 mining pipeline depends on (bd nnue-trainer-d4a.6.1,
 * Task 2): {@code baseline_mean_eval} came out strongly negative (-4255 on the 502-game corpus) and
 * the first thing to rule out was a flipped sign.
 *
 * <p>Three properties, all independent of the corpus: a position clearly winning for the side to
 * move scores positive, the score is exactly antisymmetric in the scored player when the tempo
 * frame is held fixed, and the symmetric start position favours the mover (so nothing in the eval
 * structurally penalizes the side to move).
 */
public class HandTunedEvalSignConventionTest {

  private static final int BOARD = 12;

  @Test
  public void winningForTheSideToMoveScoresPositive() {
    Board board = startPosition();
    // Player 1 owns a connected blob around its base; player 2 has nothing but its base.
    for (int r = 0; r < 3; r++) {
      for (int c = 0; c < 3; c++) {
        if (r != 0 || c != 0) {
          board.setCell(r, c, new Cell(1, CellKind.NORMAL));
        }
      }
    }

    int p1 = HandTunedEval.staticEval(board, 1, 3, null);
    int p2 = HandTunedEval.staticEval(board, 2, 3, null);
    assertTrue(p1 > 0, "player 1 is winning and to move, expected positive, got " + p1);
    assertTrue(p2 < 0, "player 2 is losing and to move, expected negative, got " + p2);
  }

  @Test
  public void scoreIsAntisymmetricInTheScoredPlayer() {
    Board board = startPosition();
    board.setCell(1, 1, new Cell(1, CellKind.NORMAL));
    board.setCell(0, 1, new Cell(1, CellKind.FORTIFIED));
    board.setCell(10, 10, new Cell(2, CellKind.NORMAL));

    // Same board, same mover (tempo frame): utility is raw(p) - raw(opponent) with two active
    // players, so swapping only the SCORED player must flip the sign exactly. This is what makes
    // a constant skew in baseline_mean_eval a property of the sampled positions rather than of
    // the eval.
    for (int mover = 1; mover <= 2; mover++) {
      int a = HandTunedEval.staticEval(board, 1, mover, 3, null);
      int b = HandTunedEval.staticEval(board, 2, mover, 3, null);
      assertEquals(a, -b, "eval must be antisymmetric with mover=" + mover);
    }
  }

  @Test
  public void symmetricStartPositionFavoursTheMover() {
    Board board = startPosition();
    // The only asymmetry on an empty board is the mover's movesLeft tempo bonus, so the side to
    // move is AHEAD at ply 0 — no eval term penalizes the mover.
    for (int mover = 1; mover <= 2; mover++) {
      int score = HandTunedEval.staticEval(board, mover, 3, null);
      assertTrue(score > 0, "start position with p" + mover + " to move scored " + score);
    }
  }

  private static Board startPosition() {
    Board board = new Board(BOARD, BOARD);
    board.setCell(0, 0, new Cell(1, CellKind.BASE));
    board.setCell(BOARD - 1, BOARD - 1, new Cell(2, CellKind.BASE));
    return board;
  }
}
