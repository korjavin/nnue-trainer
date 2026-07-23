package com.engine.nnue_trainer.search.gobot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.board.MoveAction;
import com.engine.nnue_trainer.board.Pos;
import org.junit.jupiter.api.Test;

/** Task 4: opening-book (wedge) trigger + void conditions and the live entry points. */
public class GoOpeningBookTest {

  private static Board baseBoard() {
    // 1v1 opening: p1 base top-left, p2 base bottom-right (matches GoState.baseIndex).
    Board b = new Board(12, 12);
    b.setCell(0, 0, new Cell(1, CellKind.BASE));
    b.setCell(11, 11, new Cell(2, CellKind.BASE));
    return b;
  }

  @Test
  public void freshOpeningTurnPlacesTheAnchor() {
    // p1 base (0,0) orients inward (dr=dc=+1): the wedge anchor is (1,1).
    GoState state = GoState.fromBoard(baseBoard(), 1, 3, new boolean[4]);
    MoveAction move = (MoveAction) GoOpeningBook.openingBookMove(state);
    assertNotNull(move, "book fires on a fresh opening turn");
    assertEquals(new Pos(1, 1), move.target);
  }

  @Test
  public void advancesThroughTheBlockInOrder() {
    // Anchor already placed this turn (movesLeft=2) → next is the straight advancing cell (2,1).
    Board b = baseBoard();
    b.setCell(1, 1, new Cell(1, CellKind.NORMAL));
    GoState state = GoState.fromBoard(b, 1, 2, new boolean[4]);
    MoveAction move = (MoveAction) GoOpeningBook.openingBookMove(state);
    assertNotNull(move);
    assertEquals(new Pos(2, 1), move.target);
  }

  @Test
  public void voidsOnOwnCellOutsideBlock() {
    // A mid-game own non-base cell outside the wedge → defer to search.
    Board b = baseBoard();
    b.setCell(5, 5, new Cell(1, CellKind.NORMAL));
    GoState state = GoState.fromBoard(b, 1, 3, new boolean[4]);
    assertNull(GoOpeningBook.openingBookMove(state), "own cell outside block voids the book");
  }

  @Test
  public void voidsWhenNotTheGenuineFirstTurn() {
    // Fresh bases but placed(0)+movesLeft(2) != 3 → not the opening turn.
    GoState state = GoState.fromBoard(baseBoard(), 1, 2, new boolean[4]);
    assertNull(GoOpeningBook.openingBookMove(state), "movesLeft invariant voids the book");
  }

  @Test
  public void resultWrapsTheBookMoveAsCompletedDepthZero() {
    GoState state = GoState.fromBoard(baseBoard(), 1, 3, new boolean[4]);
    GoResult result = GoOpeningBook.openingBookResult(state);
    assertNotNull(result);
    assertTrue(result.searchComplete, "book is a completed result");
    assertEquals(0, result.depth, "labelled depth-0 so the client can call it \"book\"");
    assertEquals(new Pos(1, 1), ((MoveAction) result.action).target);
  }

  @Test
  public void chooseTakesTheBookOnAFreshOpening() {
    GoState state = GoState.fromBoard(baseBoard(), 1, 3, new boolean[4]);
    GoResult result = GoBotSearcher.chooseNodeBudget(state, 100_000);
    assertNotNull(result);
    assertEquals(0, result.depth, "book short-circuits iterative deepening");
    assertEquals(new Pos(1, 1), ((MoveAction) result.action).target);
  }

  @Test
  public void chooseRunsSearchWhenBookVoids() {
    // Own cell outside the wedge voids the book → node-budget search picks a legal action.
    Board b = baseBoard();
    b.setCell(5, 5, new Cell(1, CellKind.NORMAL));
    GoState state = GoState.fromBoard(b, 1, 3, new boolean[4]);
    GoResult result = GoBotSearcher.chooseNodeBudget(state, 200_000);
    assertNotNull(result);
    assertNotNull(result.action, "search returns an action");
    assertTrue(result.depth >= 1, "search ran at least one iteration");
  }
}
