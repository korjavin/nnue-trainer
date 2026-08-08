package com.engine.nnue_trainer.search.gobot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Stage-2 self-checks for the enhanced search path: the packed TT actually produces cross-ply /
 * cross-move hits, persistence carries the previous move's subtree into the next search, and the
 * enhanced node-budget path stays deterministic.
 */
public class GoBotEnhancedTTTest {

  // Determinism/persistence here are single-threaded contracts: with lazy SMP (plan item 4)
  // helper TT entries steer the main tree run-to-run. SMP-on behavior lives in GoBotSmpTest.
  @BeforeEach
  public void smpOff() {
    GoBotSearcher.smpThreadsOverride = 0;
  }

  @AfterEach
  public void restoreSmp() {
    GoBotSearcher.smpThreadsOverride = null;
  }

  /**
   * Cross-move TT reuse: after searching a position, the position reached by playing the chosen
   * action — the previous search's most-searched subtree — must already sit in the persistent TT,
   * and a second search from there must produce hits immediately.
   */
  @Test
  public void persistentSearcherCarriesTTAcrossMoves() throws Exception {
    List<GoState> states = GoBotStagedMovegenTest.fixtureStates(5);
    assertTrue(states.size() >= 1);
    boolean checkedSecondSearch = false;
    for (GoState state : states) {
      GoBotSearcher s = GoBotSearcher.newEnhancedSearcher(state);
      GoResult first = s.searchNodeBudget(state, 20_000);
      assertNotNull(first);
      assertNotNull(first.action);

      GoState next = state.apply(first.action);
      assertNotNull(next, "chosen action must be legal");
      if (next.gameOver()) {
        continue;
      }
      assertTrue(s.ttHasEntry(next), "played-action successor missing from the persistent TT");
      if (next.currentPlayer() == s.rootPlayer()) {
        long hitsBefore = s.ttHits;
        GoResult second = s.searchNodeBudget(next, 20_000);
        assertNotNull(second);
        assertTrue(s.ttHits > hitsBefore, "second search produced no TT hits from the first");
        checkedSecondSearch = true;
      }
    }
    assertTrue(checkedSecondSearch, "no fixture position allowed a same-side follow-up search");
  }

  /** Enhanced node-budget search is still fully deterministic (fresh searcher, same budget). */
  @Test
  public void enhancedNodeBudgetIsDeterministic() throws Exception {
    List<GoState> states = GoBotStagedMovegenTest.fixtureStates(3);
    for (GoState state : states) {
      GoResult a = GoBotSearcher.newEnhancedSearcher(state).searchNodeBudget(state, 15_000);
      GoResult b = GoBotSearcher.newEnhancedSearcher(state).searchNodeBudget(state, 15_000);
      assertNotNull(a);
      assertNotNull(b);
      assertEquals(a.action, b.action, "enhanced search must be reproducible");
      assertEquals(a.score, b.score);
      assertEquals(a.nodes, b.nodes);
    }
  }

  /** The parity variant must behave exactly like the pre-enhancement static entry point. */
  @Test
  public void parityVariantIgnoresEnhancements() throws Exception {
    List<GoState> states = GoBotStagedMovegenTest.fixtureStates(3);
    for (GoState state : states) {
      GoResult parity = GoBotSearcher.chooseNodeBudget(state, 15_000, false);
      GoResult again = GoBotSearcher.chooseNodeBudget(state, 15_000, false);
      assertNotNull(parity);
      assertEquals(parity.action, again.action);
      assertEquals(parity.score, again.score);
      assertEquals(parity.nodes, again.nodes);
    }
  }
}
