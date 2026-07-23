package com.engine.nnue_trainer.search.gobot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.board.MoveAction;
import com.engine.nnue_trainer.board.Pos;
import org.junit.jupiter.api.Test;

/** Task 2 scaffolding: TT store/probe round-trip, flag constants, and stateHash keying. */
public class GoBotSearcherTest {

  private static Board baseBoard() {
    // 1v1 opening: p1 base top-left, p2 base bottom-right (matches GoState.baseIndex).
    Board b = new Board(12, 12);
    b.setCell(0, 0, new Cell(1, CellKind.BASE));
    b.setCell(11, 11, new Cell(2, CellKind.BASE));
    return b;
  }

  @Test
  public void ttStoreThenProbeReturnsEntry() {
    GoState state = GoState.fromBoard(baseBoard(), 1, 3, new boolean[4]);
    GoBotSearcher s = GoBotSearcher.newSearcher(state);
    assertFalse(s.multi, "1v1 is not maxN");
    assertEquals(1, s.root);

    long key = state.hash();
    assertNull(s.probe(key), "miss before store");

    MoveAction best = new MoveAction(new Pos(1, 1));
    TableEntry entry = TableEntry.single(5, 0, GoBotSearcher.FLAG_LOWER, best, 42);
    s.store(key, entry);

    TableEntry got = s.probe(key);
    assertSame(entry, got);
    assertEquals(5, got.depth);
    assertEquals(0, got.ply);
    assertEquals(GoBotSearcher.FLAG_LOWER, got.flag);
    assertEquals(42, got.values[0]);
    assertEquals(best, got.bestAction);

    assertNull(s.probe(key ^ 1L), "different key still a miss");
  }

  @Test
  public void flagConstantsMatchGoBotIota() {
    assertEquals(0, GoBotSearcher.FLAG_EXACT);
    assertEquals(1, GoBotSearcher.FLAG_LOWER);
    assertEquals(2, GoBotSearcher.FLAG_UPPER);
  }

  @Test
  public void stateHashDependsOnHiddenState() {
    Board b = baseBoard();
    long h3 = GoState.fromBoard(b, 1, 3, new boolean[4]).hash();
    long h3again = GoState.fromBoard(b, 1, 3, new boolean[4]).hash();
    assertEquals(h3again, h3, "hash is deterministic");

    // movesLeft, currentPlayer, and neutralUsed all feed stateHash → distinct TT keys.
    assertNotEquals(h3, GoState.fromBoard(b, 1, 2, new boolean[4]).hash(), "movesLeft in key");
    assertNotEquals(h3, GoState.fromBoard(b, 2, 3, new boolean[4]).hash(), "currentPlayer in key");
    boolean[] nu = {true, false, false, false};
    assertNotEquals(h3, GoState.fromBoard(b, 1, 3, nu).hash(), "neutralUsed in key");
  }

  @Test
  public void runningRespectsNodeLimit() {
    GoBotSearcher s =
        GoBotSearcher.newSearcher(GoState.fromBoard(baseBoard(), 1, 3, new boolean[4]));
    assertTrue(s.running(), "no limits set");
    s.nodeLimit = 10;
    s.nodes = 9;
    assertTrue(s.running());
    s.nodes = 10;
    assertFalse(s.running(), "node budget exhausted");
  }
}
