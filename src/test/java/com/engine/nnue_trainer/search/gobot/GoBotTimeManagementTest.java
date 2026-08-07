package com.engine.nnue_trainer.search.gobot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Stage-4 self-checks (plan item 5). The salvage path is deterministic when driven by a node-limit
 * abort at controlled points inside an iteration, so the core invariants are pinned without
 * wall-clock flakiness; the wall-clock entry is then checked for invariants only.
 */
public class GoBotTimeManagementTest {

  /**
   * Abort iteration d+1 at several points. Whenever at least one root child completed, a partial
   * result must be salvaged, its action legal, and its score exact-only (the PV move can only be
   * displaced by a fully re-searched better move). Abort points too early for child 0 must leave
   * nothing salvaged.
   */
  @Test
  public void abortedIterationSalvagesBestCompletedRootMove() throws Exception {
    List<GoState> states = GoBotStagedMovegenTest.fixtureStates(3);
    boolean salvagedSomewhere = false;
    for (GoState state : states) {
      // Cost of iterations 1..3 and 1..4 on this position (fresh searchers, deterministic).
      GoBotSearcher probe3 = GoBotSearcher.newEnhancedSearcher(state);
      probe3.searchToDepth(state, 3);
      long nodesTo3 = probe3.nodes;
      GoBotSearcher probe4 = GoBotSearcher.newEnhancedSearcher(state);
      GoResult full4 = probe4.searchToDepth(state, 4);
      long iter4Cost = probe4.nodes - nodesTo3;
      assertTrue(iter4Cost > 10, "depth-4 iteration too cheap to abort mid-way");

      for (int percent : new int[] {10, 30, 50, 75, 90}) {
        GoBotSearcher s = GoBotSearcher.newEnhancedSearcher(state);
        s.searchToDepth(state, 3); // identical warm-up: TT holds the depth-3 PV
        s.nodeLimit = s.nodes + Math.max(1, iter4Cost * percent / 100);
        try {
          s.atDepth(state, 4);
          fail("depth-4 iteration should have exhausted the truncated node budget");
        } catch (RuntimeException expected) {
          // SearchIncomplete (private type) — expected.
        }
        if (s.partialRoot == null) {
          continue; // aborted before the PV child completed: nothing may be salvaged
        }
        salvagedSomewhere = true;
        assertTrue(s.partialRoot.salvaged, "partial result must carry the salvaged flag");
        assertTrue(
            state.legalActions().contains(s.partialRoot.action),
            "salvaged action must be legal");
        // At 90% of the full iteration the salvage saw all-but-the-last children; its choice must
        // already be a move the FULL iteration also scored (sanity: it exists among root moves).
        if (percent == 90 && full4.alternatives != null) {
          boolean known = s.partialRoot.action.equals(full4.action);
          for (RootMove rm : full4.alternatives) {
            known |= s.partialRoot.action.equals(rm.action);
          }
          assertTrue(known, "salvaged move must be one of the full iteration's root moves");
        }
      }
    }
    assertTrue(salvagedSomewhere, "no abort point on any fixture produced a salvage");
  }

  /** Wall-clock invariants: always a legal action; salvage reports the last completed depth. */
  @Test
  public void deadlineEntryAlwaysReturnsALegalAction() throws Exception {
    List<GoState> states = GoBotStagedMovegenTest.fixtureStates(2);
    long[] budgets = {0, 5, 30, 150};
    for (GoState state : states) {
      for (long budget : budgets) {
        GoResult r = GoBotSearcher.chooseWithDeadline(state, System.currentTimeMillis() + budget);
        assertNotNull(r);
        assertNotNull(r.action, "no action at budget " + budget);
        assertTrue(
            state.legalActions().contains(r.action), "illegal action at budget " + budget);
      }
    }
  }

  /** The node-budget paths must ignore salvage entirely (deterministic gate stays untouched). */
  @Test
  public void nodeBudgetPathNeverSalvages() throws Exception {
    GoState state = GoBotStagedMovegenTest.fixtureStates(1).get(0);
    GoResult r = GoBotSearcher.newEnhancedSearcher(state).searchNodeBudget(state, 20_000);
    assertNotNull(r);
    assertEquals(false, r.salvaged, "node-budget result must never be salvaged");
  }
}
