package com.engine.nnue_trainer.mcts;

import com.engine.nnue_trainer.board.Action;
import com.engine.nnue_trainer.search.gobot.GoState;
import java.util.Arrays;
import java.util.List;

/**
 * Prior policy over the legal actions of a position, queried at node expansion (plan
 * 20260807-mcts-az-feasibility D1): always computed from the node's <b>own</b> {@code
 * currentPlayer()} so the net-input frame and the prior frame coincide by construction.
 *
 * <p>Contract: returns one probability per action, same order as {@code actions}, summing to ~1.
 */
public interface PolicyPrior {

  float[] priors(GoState state, List<Action> actions);

  /**
   * Uniform over legal actions — the Phase 1 baseline (gauntlet arm (a)). The plan defines no
   * heuristic prior beyond uniform, so there deliberately isn't one here.
   */
  PolicyPrior UNIFORM =
      (state, actions) -> {
        float[] p = new float[actions.size()];
        Arrays.fill(p, 1.0f / actions.size());
        return p;
      };
}
