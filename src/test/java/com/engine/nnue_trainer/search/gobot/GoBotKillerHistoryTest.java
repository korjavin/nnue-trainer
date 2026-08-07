package com.engine.nnue_trainer.search.gobot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.engine.nnue_trainer.board.Action;
import com.engine.nnue_trainer.board.MoveAction;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Stage-3 self-checks (plan item 3): a recorded killer jumps the ordering queue (right after the TT
 * move, ahead of every static-bonus sibling), history feeds the ordering, and a real enhanced
 * search actually records killers/history — so the heuristics cannot silently regress to dead code.
 * The parity oracle path must order exactly as before (enhanced == false skips both).
 */
public class GoBotKillerHistoryTest {

  @Test
  public void recordedKillerIsOrderedFirstAmongQuietMoves() throws Exception {
    List<GoState> states = GoBotStagedMovegenTest.fixtureStates(3);
    assertTrue(states.size() >= 1);
    GoState state = states.get(0);
    GoBotSearcher s = GoBotSearcher.newEnhancedSearcher(state);
    s.searchNodeBudget(state, 100); // allocates per-board heuristic state cheaply
    s.nodeLimit = 0; // lift the exhausted budget so direct orderedActions calls run

    List<Action> plain = s.orderedActions(state, null, false, 5);
    assertTrue(plain.size() >= 3, "need a branchy position");
    // Pick a quiet move the static ordering puts LAST and declare it the ply-5 killer.
    Action lateQuiet = null;
    for (int i = plain.size() - 1; i >= 0; i--) {
      if (plain.get(i) instanceof MoveAction) {
        lateQuiet = plain.get(i);
        break;
      }
    }
    assertNotEquals(plain.get(0), lateQuiet, "fixture ordering has no late move to promote");
    s.killers[5][0] = lateQuiet;

    List<Action> reordered = s.orderedActions(state, null, false, 5);
    assertEquals(lateQuiet, reordered.get(0), "killer must jump to the front at its ply");
    // Other plies are unaffected.
    assertEquals(plain, s.orderedActions(state, null, false, 4), "killer is per-ply");
  }

  @Test
  public void ttMoveStillOutranksKiller() throws Exception {
    GoState state = GoBotStagedMovegenTest.fixtureStates(1).get(0);
    GoBotSearcher s = GoBotSearcher.newEnhancedSearcher(state);
    s.searchNodeBudget(state, 100); // allocates per-board heuristic state cheaply
    s.nodeLimit = 0; // lift the exhausted budget so direct orderedActions calls run
    List<Action> plain = s.orderedActions(state, null, false, 3);
    Action killer = plain.get(plain.size() - 1);
    Action ttMove = plain.get(plain.size() - 2);
    s.killers[3][0] = killer;
    List<Action> reordered = s.orderedActions(state, ttMove, true, 3);
    assertEquals(ttMove, reordered.get(0), "TT move outranks the killer");
    assertEquals(killer, reordered.get(1), "killer right behind the TT move");
  }

  @Test
  public void historyBiasesQuietOrdering() throws Exception {
    GoState state = GoBotStagedMovegenTest.fixtureStates(1).get(0);
    GoBotSearcher s = GoBotSearcher.newEnhancedSearcher(state);
    s.searchNodeBudget(state, 100); // allocates per-board heuristic state cheaply
    s.nodeLimit = 0; // lift the exhausted budget so direct orderedActions calls run
    List<Action> plain = s.orderedActions(state, null, false, 2);
    Action late = null;
    for (int i = plain.size() - 1; i >= 0; i--) {
      if (plain.get(i) instanceof MoveAction) {
        late = plain.get(i);
        break;
      }
    }
    MoveAction move = (MoveAction) late;
    int mover = state.currentPlayer();
    s.history[mover - 1][move.target.row * state.cols() + move.target.col] = 8_000;
    List<Action> reordered = s.orderedActions(state, null, false, 2);
    assertTrue(
        reordered.indexOf(late) < plain.indexOf(late),
        "history bump must move a quiet move up the order");
  }

  /** A real enhanced search must populate killers and history (they are recorded on cutoffs). */
  @Test
  public void enhancedSearchRecordsKillersAndHistory() throws Exception {
    GoState state = GoBotStagedMovegenTest.fixtureStates(1).get(0);
    GoBotSearcher s = GoBotSearcher.newEnhancedSearcher(state);
    s.searchNodeBudget(state, 30_000);
    boolean anyKiller = false;
    for (Action[] slot : s.killers) {
      anyKiller |= slot[0] != null;
    }
    assertTrue(anyKiller, "no killer recorded across a 30k-node search");
    long historyMass = 0;
    for (int[] side : s.history) {
      for (int v : side) {
        historyMass += v;
      }
    }
    assertTrue(historyMass > 0, "no history recorded across a 30k-node search");
  }
}
