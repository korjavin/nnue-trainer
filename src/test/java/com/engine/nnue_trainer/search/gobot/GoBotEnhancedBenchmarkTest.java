package com.engine.nnue_trainer.search.gobot;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Opt-in wall-clock benchmark for the enhanced search path (plan items 1-3): {@code
 * chooseNodeBudget(60000)} over the first ~10 non-book fixture positions, enhanced vs the
 * byte-exact GoBot oracle path. Set {@code GOBOT_ENHANCED_BENCH=1} to run — numbers are
 * machine-specific and it burns real CPU, so it stays off the default test path.
 */
public class GoBotEnhancedBenchmarkTest {

  private static final long NODE_BUDGET = 60_000;

  @Test
  public void enhancedVsOracleWallTime() throws Exception {
    assumeTrue(
        System.getenv("GOBOT_ENHANCED_BENCH") != null,
        "set GOBOT_ENHANCED_BENCH=1 to run the benchmark");

    List<GoState> states = GoBotStagedMovegenTest.fixtureStates(10);
    assertTrue(states.size() >= 5, "too few fixture positions");

    // Warm up JIT on both paths once.
    GoBotSearcher.chooseNodeBudget(states.get(0), NODE_BUDGET, false);
    GoBotSearcher.chooseNodeBudget(states.get(0), NODE_BUDGET, true);

    long oracleMs = 0;
    long oracleNodes = 0;
    long enhancedMs = 0;
    long enhancedNodes = 0;
    for (GoState state : states) {
      long t0 = System.nanoTime();
      GoResult a = GoBotSearcher.chooseNodeBudget(state, NODE_BUDGET, false);
      oracleMs += (System.nanoTime() - t0) / 1_000_000;
      oracleNodes += a.nodes;

      long t1 = System.nanoTime();
      GoResult b = GoBotSearcher.chooseNodeBudget(state, NODE_BUDGET, true);
      enhancedMs += (System.nanoTime() - t1) / 1_000_000;
      enhancedNodes += b.nodes;
    }
    double oracleNps = oracleNodes * 1000.0 / Math.max(1, oracleMs);
    double enhancedNps = enhancedNodes * 1000.0 / Math.max(1, enhancedMs);
    System.out.printf(
        "=== enhanced-search benchmark (%d positions @ %,d-node budget) ===%n"
            + "  oracle  : %,d nodes / %,d ms = %,.0f nps%n"
            + "  enhanced: %,d nodes / %,d ms = %,.0f nps%n"
            + "  NPS ratio (enhanced/oracle): %.2fx; wall-time ratio (oracle/enhanced): %.2fx%n",
        states.size(),
        NODE_BUDGET,
        oracleNodes,
        oracleMs,
        oracleNps,
        enhancedNodes,
        enhancedMs,
        enhancedNps,
        enhancedNps / Math.max(1, oracleNps),
        (double) oracleMs / Math.max(1, enhancedMs));
    assertTrue(enhancedMs > 0 && oracleMs > 0);
  }
}
