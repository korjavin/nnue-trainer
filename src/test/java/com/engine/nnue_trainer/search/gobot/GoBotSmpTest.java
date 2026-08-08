package com.engine.nnue_trainer.search.gobot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Lazy SMP (plan item 4). SMP off must be byte-identical to the single-threaded search; SMP on must
 * return a legal move under its deadline with helpers sharing (and hitting) the one packed TT; the
 * 30s race hammer is env-gated ({@code GOBOT_SMP_HAMMER=1}) so CI stays cheap.
 */
public class GoBotSmpTest {

  @AfterEach
  public void restoreSmp() {
    GoBotSearcher.smpThreadsOverride = null;
  }

  @Test
  public void smpOffIsDeterministic() throws Exception {
    GoBotSearcher.smpThreadsOverride = 0;
    for (GoState state : GoBotStagedMovegenTest.fixtureStates(3)) {
      GoResult a = GoBotSearcher.chooseNodeBudget(state, 20_000);
      GoResult b = GoBotSearcher.chooseNodeBudget(state, 20_000);
      assertEquals(a.action, b.action);
      assertEquals(a.score, b.score);
      assertEquals(a.depth, b.depth);
      assertEquals(a.nodes, b.nodes, "node accounting must be reproducible with SMP off");
    }
  }

  @Test
  public void smpOnReturnsLegalMoveUnderDeadline() throws Exception {
    GoBotSearcher.smpThreadsOverride = 4;
    for (GoState state : GoBotStagedMovegenTest.fixtureStates(5)) {
      GoBotSearcher s = GoBotSearcher.newEnhancedSearcher(state);
      long start = System.currentTimeMillis();
      GoResult r = s.searchWithDeadline(state, start + 300);
      long elapsed = System.currentTimeMillis() - start;
      assertNotNull(r);
      assertNotNull(r.action);
      assertTrue(state.legalActions().contains(r.action), "SMP move must be legal: " + r.action);
      assertTrue(elapsed < 2_000, "deadline + helper join took " + elapsed + " ms");
      assertNotNull(s.lastHelpers);
      assertEquals(3, s.lastHelpers.size());
      long helperNodes = 0;
      long helperTtHits = 0;
      for (GoBotSearcher h : s.lastHelpers) {
        assertNull(h.helperFailure, String.valueOf(h.helperFailure));
        helperNodes += h.nodes;
        helperTtHits += h.ttHits;
      }
      assertTrue(helperNodes > 0, "helpers never searched");
      // Helpers and main share one TT and interleave stores from depth 2/3 upward, so hits on
      // the shared table (main and helpers both) are the cross-thread currency of lazy SMP.
      assertTrue(helperTtHits > 0, "helpers never hit the shared TT");
      assertTrue(s.ttHits > 0, "main thread never hit the shared TT");
    }
  }

  @Test
  public void sharedTableIsVisibleAcrossSearchers() throws Exception {
    GoBotSearcher.smpThreadsOverride = 0;
    GoState state = GoBotStagedMovegenTest.fixtureStates(1).get(0);
    GoBotSearcher a = GoBotSearcher.newEnhancedSearcher(state);
    a.searchNodeBudget(state, 30_000);
    // A helper-style searcher constructed on a's table sees a's entries before searching a node…
    GoBotSearcher b = new GoBotSearcher(a.rootPlayer(), false, true, a.tt);
    assertTrue(b.ttHasEntry(state), "entry stored by searcher a must be visible through b");
    // …and its own search probes into them.
    b.searchNodeBudget(state, 5_000);
    assertTrue(b.ttHits > 0, "searcher b never hit entries in the shared table");
  }

  /** 30s race hammer, env-gated: rotating fixture positions, 4 threads, 200 ms deadlines. */
  @Test
  public void smpHammer() throws Exception {
    assumeTrue("1".equals(System.getenv("GOBOT_SMP_HAMMER")), "set GOBOT_SMP_HAMMER=1");
    GoBotSearcher.smpThreadsOverride = 4;
    List<GoState> states = GoBotStagedMovegenTest.fixtureStates(20);
    long end = System.currentTimeMillis() + 30_000;
    int moves = 0;
    while (System.currentTimeMillis() < end) {
      GoState state = states.get(moves % states.size());
      GoBotSearcher s = GoBotSearcher.newEnhancedSearcher(state);
      GoResult r = s.searchWithDeadline(state, System.currentTimeMillis() + 200);
      assertNotNull(r.action);
      assertTrue(state.legalActions().contains(r.action));
      if (s.lastHelpers != null) {
        for (GoBotSearcher h : s.lastHelpers) {
          assertNull(h.helperFailure, String.valueOf(h.helperFailure));
        }
      }
      moves++;
    }
    System.out.println("smpHammer: " + moves + " SMP searches, no failures");
    assertTrue(moves > 0);
  }

  /** Wall-clock sanity, env-gated ({@code GOBOT_SMP_DEPTH_CHECK=1}): depth at 1s, SMP 4 vs 0. */
  @Test
  public void smpDepthCheck() throws Exception {
    assumeTrue("1".equals(System.getenv("GOBOT_SMP_DEPTH_CHECK")), "set GOBOT_SMP_DEPTH_CHECK=1");
    List<GoState> states = GoBotStagedMovegenTest.fixtureStates(5);
    for (int smp : new int[] {0, 4}) {
      GoBotSearcher.smpThreadsOverride = smp;
      for (int i = 0; i < states.size(); i++) {
        GoState state = states.get(i);
        GoBotSearcher s = GoBotSearcher.newEnhancedSearcher(state);
        GoResult r = s.searchWithDeadline(state, System.currentTimeMillis() + 1_000);
        long helperNodes = 0;
        if (s.lastHelpers != null) {
          for (GoBotSearcher h : s.lastHelpers) {
            helperNodes += h.nodes;
          }
        }
        System.out.printf(
            "smp=%d pos=%d depth=%d mainNodes=%d helperNodes=%d action=%s%n",
            smp, i, r.depth, r.nodes, helperNodes, r.action);
      }
    }
  }
}
