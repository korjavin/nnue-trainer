package com.engine.nnue_trainer.search.gobot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Plan items 6-7 self-checks (aspiration windows + turn-aware LMR), all on the enhanced path with
 * SMP pinned off so the searches are deterministic: aspiration must be a pure node-saver (same
 * move, same score as the full-window run, with the fail/re-search path actually exercised), LMR
 * must never reduce a turn-ending action (counter tripwire), and with both flags off the search is
 * the byte-identical pre-feature enhanced baseline. The parity suites cover the enhanced=false
 * oracle; both features are additionally gated on {@code enhanced} so that path never sees them.
 */
public class GoBotAspirationLmrTest {

  @BeforeEach
  public void pinSingleThread() {
    GoBotSearcher.smpThreadsOverride = 0;
  }

  @AfterEach
  public void restoreFlags() {
    GoBotSearcher.smpThreadsOverride = null;
    GoBotSearcher.aspirationOverride = null;
    GoBotSearcher.lmrOverride = null;
  }

  private static GoBotSearcher searcher(GoState state, boolean aspiration, boolean lmr) {
    GoBotSearcher.aspirationOverride = aspiration;
    GoBotSearcher.lmrOverride = lmr;
    return GoBotSearcher.newEnhancedSearcher(state);
  }

  /**
   * Aspiration is re-search-correct: across fixture positions (several of which fail their window —
   * asserted, so the widening path is genuinely exercised) the aspirated search returns the same
   * move and the same exact score as the full-window search.
   */
  @Test
  public void aspirationMatchesFullWindowSearch() throws Exception {
    List<GoState> states = GoBotOrderingTableTest.fixtureStates(12);
    assertTrue(states.size() >= 8, "fixture yielded too few positions");
    long windowFails = 0;
    for (GoState state : states) {
      GoBotSearcher full = searcher(state, false, false);
      GoResult fullResult = full.searchToDepth(state, 5);
      GoBotSearcher aspirated = searcher(state, true, false);
      GoResult aspResult = aspirated.searchToDepth(state, 5);
      assertEquals(fullResult.action, aspResult.action, "aspiration changed the chosen move");
      assertEquals(fullResult.score, aspResult.score, "aspiration changed the exact score");
      assertEquals(0, full.aspirationFailLows + full.aspirationFailHighs, "flag-off run aspirated");
      windowFails += aspirated.aspirationFailLows + aspirated.aspirationFailHighs;
    }
    assertTrue(windowFails > 0, "no window fail on any fixture — re-search path not exercised");
  }

  /** Turn-aware LMR reduces real quiet moves but never a turn-ending action (plan's tempo trap). */
  @Test
  public void lmrNeverReducesTurnEndingActions() throws Exception {
    List<GoState> states = GoBotOrderingTableTest.fixtureStates(5);
    long reductions = 0;
    long turnEnding = 0;
    for (GoState state : states) {
      GoBotSearcher s = searcher(state, false, true);
      s.searchToDepth(state, 5);
      reductions += s.lmrReductions;
      turnEnding += s.lmrTurnEndingReductions;
    }
    assertTrue(reductions > 0, "LMR never fired across the fixtures");
    assertEquals(0, turnEnding, "a turn-ending action was reduced");
  }

  /**
   * Both features off = the pre-feature enhanced baseline: deterministic, zero feature activity.
   */
  @Test
  public void featuresOffIsUnchangedEnhancedBaseline() throws Exception {
    GoState state = GoBotOrderingTableTest.fixtureStates(1).get(0);
    GoBotSearcher a = searcher(state, false, false);
    GoResult ra = a.searchToDepth(state, 5);
    GoBotSearcher b = searcher(state, false, false);
    GoResult rb = b.searchToDepth(state, 5);
    assertEquals(ra.action, rb.action);
    assertEquals(ra.score, rb.score);
    assertEquals(a.nodes, b.nodes, "flags-off search must be node-identical run to run");
    for (GoBotSearcher s : List.of(a, b)) {
      assertEquals(0, s.aspirationFailLows + s.aspirationFailHighs);
      assertEquals(0, s.lmrReductions + s.lmrReSearches + s.lmrTurnEndingReductions);
    }
  }

  /**
   * Measurement probe (env-gated: {@code GOBOT_ASPLMR_CHECK=1}): fixed depth 5 over ~50 fixture
   * positions, the four feature combinations, printing nodes + fail/re-search counters and the node
   * reduction vs the both-off baseline. Evidence table for the plan item 6/7 rollout.
   */
  @Test
  public void aspirationLmrNodeCheck() throws Exception {
    assumeTrue("1".equals(System.getenv("GOBOT_ASPLMR_CHECK")), "set GOBOT_ASPLMR_CHECK=1");
    List<GoState> states =
        GoBotOrderingTableTest.fixtureStates(Integer.getInteger("gobot.asplmr.positions", 50));
    int depth = Integer.getInteger("gobot.asplmr.depth", 5);
    long baseline = 0;
    boolean[][] configs = {{false, false}, {true, false}, {false, true}, {true, true}};
    for (boolean[] cfg : configs) {
      long nodes = 0;
      long failLo = 0;
      long failHi = 0;
      long red = 0;
      long reSearch = 0;
      long wall = System.currentTimeMillis();
      for (GoState state : states) {
        GoBotSearcher s = searcher(state, cfg[0], cfg[1]);
        s.searchToDepth(state, depth);
        nodes += s.nodes;
        failLo += s.aspirationFailLows;
        failHi += s.aspirationFailHighs;
        red += s.lmrReductions;
        reSearch += s.lmrReSearches;
      }
      if (baseline == 0) {
        baseline = nodes;
      }
      System.out.printf(
          "asp=%d lmr=%d depth=%d pos=%d nodes=%,d (%+.1f%% vs off/off) aspFailLow=%d"
              + " aspFailHigh=%d lmrRed=%,d lmrReSearch=%,d (%.1f%%) wallMs=%d%n",
          cfg[0] ? 1 : 0,
          cfg[1] ? 1 : 0,
          depth,
          states.size(),
          nodes,
          100.0 * (nodes - baseline) / baseline,
          failLo,
          failHi,
          red,
          reSearch,
          red == 0 ? 0.0 : 100.0 * reSearch / red,
          System.currentTimeMillis() - wall);
    }
    assertTrue(baseline > 0);
  }
}
