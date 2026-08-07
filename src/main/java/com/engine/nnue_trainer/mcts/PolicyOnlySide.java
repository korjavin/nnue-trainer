package com.engine.nnue_trainer.mcts;

import com.engine.nnue_trainer.board.Action;
import com.engine.nnue_trainer.search.gobot.GoState;
import java.util.List;
import java.util.Random;

/**
 * Search-free player: plays straight from the conv policy net's move distribution — the throughput
 * arm for self-play generation (and a deliberately weak opponent). One net forward per move, no
 * tree.
 *
 * <p>Legality masking and the neutral-pair head are handled by {@link PolicyNetPrior#priors}, which
 * scores only the {@code legalActions()} list (144 move logits for {@code MoveAction}, factored
 * pair utilities {@code u[i]+u[j]+b} for {@code PlaceNeutralsAction}) and softmaxes over exactly
 * that set — the same conversion {@code MctsSearcher.expand} uses, so an illegal action can never
 * be produced by construction.
 *
 * <p>{@code temperature <= 0} plays argmax (deterministic, ties by board order); {@code > 0}
 * samples {@code p_i^(1/T)} with the seeded RNG (τ=1 reproduces the net's own distribution — the
 * self-play setting).
 */
public final class PolicyOnlySide {

  private final PolicyPrior net;
  private final double temperature;
  private final Random random;

  public PolicyOnlySide(PolicyPrior net, double temperature, long seed) {
    this.net = net;
    this.temperature = temperature;
    this.random = new Random(seed);
  }

  /** The chosen action, or {@code null} on a terminal/stuck position. */
  public Action choose(GoState state) {
    List<Action> legal = state.legalActions();
    if (legal.isEmpty()) {
      return null;
    }
    float[] p = net.priors(state, legal);
    return legal.get(temperature > 0 ? sample(p) : argmax(p));
  }

  /** The net's masked distribution over {@code legal} — the policy target for self-play rows. */
  public float[] distribution(GoState state, List<Action> legal) {
    return net.priors(state, legal);
  }

  static int argmax(float[] p) {
    int best = 0;
    for (int i = 1; i < p.length; i++) {
      if (p[i] > p[best]) {
        best = i;
      }
    }
    return best;
  }

  private int sample(float[] p) {
    double[] w = new double[p.length];
    double sum = 0;
    for (int i = 0; i < p.length; i++) {
      // ponytail: pow per action per move; precompute in log-space if profiling ever cares.
      w[i] = Math.pow(p[i], 1.0 / temperature);
      sum += w[i];
    }
    if (sum <= 0) {
      return argmax(p);
    }
    double r = random.nextDouble() * sum;
    for (int i = 0; i < w.length; i++) {
      r -= w[i];
      if (r < 0) {
        return i;
      }
    }
    return w.length - 1;
  }
}
