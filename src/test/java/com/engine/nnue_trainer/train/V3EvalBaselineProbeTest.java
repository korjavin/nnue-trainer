package com.engine.nnue_trainer.train;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The one bit of arithmetic in the baseline probe whose numbers land in the capacity report. */
public class V3EvalBaselineProbeTest {

  @Test
  public void nearestRankPercentile() {
    int[] sorted = {-100, -50, -10, 0, 10, 20, 30, 40, 50, 100};
    assertEquals(-100, V3EvalBaselineProbe.percentile(sorted, 0));
    assertEquals(-100, V3EvalBaselineProbe.percentile(sorted, 10));
    assertEquals(10, V3EvalBaselineProbe.percentile(sorted, 50));
    assertEquals(50, V3EvalBaselineProbe.percentile(sorted, 90)); // nearest rank: index 8, not 7
    assertEquals(100, V3EvalBaselineProbe.percentile(sorted, 100));
    // Single element: every percentile is that element, no index escapes the array.
    assertEquals(7, V3EvalBaselineProbe.percentile(new int[] {7}, 25));
  }
}
