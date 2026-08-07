package com.engine.nnue_trainer.mcts;

/**
 * A gauntlet side played by {@link MctsSearcher} instead of the GoBot alpha-beta search — the Phase
 * 1 experiment arm ({@code GauntletMatch} dispatches on this type, like the V3Eval sides).
 */
public final class MctsSide {

  public final MctsSearcher.Config config;

  /**
   * Per-move simulation budget; {@code > 0} plays fixed-compute (evaluation-ladder rung 2), {@code
   * 0} plays the match's per-move wall-clock deadline (rung 1, the production condition).
   */
  public final int sims;

  public MctsSide(MctsSearcher.Config config, int sims) {
    this.config = config;
    this.sims = sims;
  }
}
