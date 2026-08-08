package com.engine.nnue_trainer.search.gobot;

/**
 * Cross-package test access to {@link GoBotSearcher#smpThreadsOverride}: tests asserting
 * single-threaded determinism contracts (reproducible node-budget searches) must pin lazy SMP off,
 * and helper-thread TT traffic makes those runs non-deterministic otherwise.
 */
public final class SmpTestPin {

  private SmpTestPin() {}

  public static void off() {
    GoBotSearcher.smpThreadsOverride = 0;
  }

  public static void reset() {
    GoBotSearcher.smpThreadsOverride = null;
  }
}
