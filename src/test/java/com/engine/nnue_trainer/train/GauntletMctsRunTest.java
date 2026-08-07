package com.engine.nnue_trainer.train;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The Phase 2 generation gate: promote iff (W + 0.5·D)/N ≥ threshold (plan's ≥55%/400 bar). */
class GauntletMctsRunTest {

  @Test
  void gateArithmetic() {
    // Exactly 55% passes (the gate is inclusive), one draw short fails.
    assertTrue(GauntletMctsRun.promote(220, 180, 0, 0.55)); // 55.0% of 400
    assertFalse(GauntletMctsRun.promote(219, 180, 1, 0.55)); // 54.875%
    // Draws count half: 200W + 40D + 160L = (200+20)/400 = 55%.
    assertTrue(GauntletMctsRun.promote(200, 160, 40, 0.55));
    assertFalse(GauntletMctsRun.promote(200, 161, 39, 0.55));
    // Degenerate inputs never promote.
    assertFalse(GauntletMctsRun.promote(0, 0, 0, 0.55));
    // All-draw match sits at exactly 50%.
    assertTrue(GauntletMctsRun.promote(0, 0, 400, 0.50));
    assertFalse(GauntletMctsRun.promote(0, 0, 400, 0.55));
  }
}
