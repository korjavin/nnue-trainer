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
 * <p>The SIGN evidence is directional and lives in {@link #winningForTheSideToMoveScoresPositive}
 * and {@link #symmetricStartPositionFavoursTheMover} — a globally flipped eval fails both.
 * Antisymmetry is NOT sign evidence: with two active players {@code HandTunedEval} computes {@code
 * raw(p) - raw(opponent)} exactly, so it is an algebraic identity a flipped eval satisfies too. It
 * is pinned here only as a regression guard on the {@code activeCount} division.
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
    // players, so swapping only the SCORED player must flip the sign exactly. An identity, not a
    // sign check -- what it buys is that a constant skew in baseline_mean_eval is a property of
    // the sampled positions rather than of an asymmetry in the eval's two-player utility.
    for (int mover = 1; mover <= 2; mover++) {
      int a = HandTunedEval.staticEval(board, 1, mover, 3, null);
      int b = HandTunedEval.staticEval(board, 2, mover, 3, null);
      assertEquals(a, -b, "eval must be antisymmetric with mover=" + mover);
    }
  }

  /**
   * The other half of the antisymmetry story: with a player inactive the identity above STOPS
   * holding, because the eliminated player is scored as a flat {@code -MATE_SCORE/2} rather than
   * through the {@code raw(p) - raw(opponent)} difference. Those positions are 5e8-magnitude
   * outliers against a corpus that spans ±3e4, which is why {@link
   * com.engine.nnue_trainer.train.GamesDbReplay} stops snapshotting once the game is over — and
   * what the probe's antisymmetry counter actually detects. The board is hand-built: the rules
   * cannot capture a BASE, so a decided replay position reaches this state via stuck-player
   * elimination instead.
   */
  @Test
  public void anInactivePlayerBreaksAntisymmetryAndScoresMate() {
    Board board = startPosition();
    // Player 2's base corner is now a player 1 cell — p2 is eliminated.
    board.setCell(BOARD - 1, BOARD - 1, new Cell(1, CellKind.FORTIFIED));
    board.setCell(BOARD - 2, BOARD - 2, new Cell(1, CellKind.NORMAL));

    int loser = HandTunedEval.staticEval(board, 2, 1, 3, null);
    int winner = HandTunedEval.staticEval(board, 1, 1, 3, null);
    assertEquals(-1_000_000_000 / 2, loser, "eliminated player is scored -MATE_SCORE/2");
    // `winner != -loser` alone would pass for every value but one; the point is that the survivor
    // stays in the ordinary eval band while the loser is a 5e8 outlier.
    assertTrue(winner > 0, "the surviving player must score positive, got " + winner);
    assertTrue(
        Math.abs(winner) < 1_000_000,
        "the survivor must not inherit the mate magnitude, got " + winner);
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
