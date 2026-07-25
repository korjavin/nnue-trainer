package com.engine.nnue_trainer.search.gobot;

import com.engine.nnue_trainer.board.Action;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Shared, seeded exploration sampler for the GoBot play path. Reused by BOTH the live data-gen
 * challenger ({@code GameLoopHandler}) and the offline generator ({@code SelfPlayGenerator}) so the
 * near-best sampling lives in exactly one place.
 *
 * <p><b>Hard constraint:</b> with {@link #enabled} false this is byte-identical to plain argmax —
 * {@link #sampleMove} returns {@code r.action} and {@link #sampleOpening} returns {@code null}
 * (keep the canonical book move). Exploration is opt-in only.
 *
 * <p>When enabled, {@link #sampleMove} draws a "near-best" move by softmax-weighting the best-first
 * candidate set {@code [best] + alternatives} over their scores. Same seed ⇒ same diverse sequence.
 */
public final class GoBotExploration {
  public final boolean enabled;
  public final double temperature;
  public final Random random;

  public GoBotExploration(boolean enabled, double temperature, Random random) {
    this.enabled = enabled;
    this.temperature = temperature;
    this.random = random != null ? random : new Random();
  }

  /**
   * Read exploration config from system properties (falling back to env), mirroring {@code
   * GameLoopHandler.gobotSearchFromEnv}. {@code enableKey} is a boolean gate (default false);
   * {@code tempKey} the softmax temperature (default {@code defaultTemp}); {@code seedKey} a long
   * seed (0/absent ⇒ nondeterministic {@link Random}, else seeded).
   */
  public static GoBotExploration fromEnv(
      String enableKey, String tempKey, String seedKey, double defaultTemp) {
    boolean on = Boolean.parseBoolean(prop(enableKey));
    double temp = parseDouble(prop(tempKey), defaultTemp);
    long seed = parseLong(prop(seedKey), 0L);
    Random rng = seed != 0L ? new Random(seed) : new Random();
    return new GoBotExploration(on, temp, rng);
  }

  private static String prop(String key) {
    return System.getProperty(key, System.getenv(key));
  }

  private static double parseDouble(String s, double dflt) {
    if (s == null || s.isBlank()) return dflt;
    try {
      return Double.parseDouble(s.trim());
    } catch (NumberFormatException e) {
      return dflt;
    }
  }

  private static long parseLong(String s, long dflt) {
    if (s == null || s.isBlank()) return dflt;
    try {
      return Long.parseLong(s.trim());
    } catch (NumberFormatException e) {
      return dflt;
    }
  }

  /**
   * Pick a move from a search result. Disabled, empty/null alternatives, non-positive temperature,
   * or a book result ⇒ argmax ({@code r.action}). Otherwise softmax-sample the best-first candidate
   * set {@code [best] + alternatives}.
   */
  public Action sampleMove(GoResult r) {
    if (r == null) return null;
    if (!enabled || temperature <= 0.0 || r.alternatives == null || r.alternatives.isEmpty()) {
      return r.action;
    }
    List<Action> actions = new ArrayList<>();
    List<Integer> scores = new ArrayList<>();
    actions.add(r.action);
    scores.add(r.score);
    for (RootMove m : r.alternatives) {
      actions.add(m.action);
      scores.add(m.score);
    }
    // Softmax over (score - maxScore) scaled by temperature so temperature is O(1). NNUE_SCALE
    // only calibrates the NNUE leaf (scores inside ±1000); the live challenger's default leaf is
    // HAND_TUNED, whose root scores run an order of magnitude larger — there a fixed 1000 collapses
    // the distribution onto argmax and the knob does nothing. Widening to the observed candidate
    // band fixes that and is a no-op whenever the band already fits inside NNUE_SCALE.
    int maxScore = scores.get(0);
    int minScore = scores.get(0);
    for (int s : scores) {
      if (s > maxScore) maxScore = s;
      if (s < minScore) minScore = s;
    }
    double scale = Math.max(GoBotSearcher.NNUE_SCALE, (double) maxScore - minScore) * temperature;
    double[] weights = new double[scores.size()];
    double total = 0.0;
    for (int i = 0; i < scores.size(); i++) {
      double w = Math.exp((scores.get(i) - maxScore) / scale);
      weights[i] = w;
      total += w;
    }
    // Inverse-CDF sample.
    double pick = random.nextDouble() * total;
    double acc = 0.0;
    for (int i = 0; i < weights.length; i++) {
      acc += weights[i];
      if (pick < acc) return actions.get(i);
    }
    return actions.get(actions.size() - 1); // float-rounding fallback
  }

  /**
   * On the opening turn, pick a uniform-random legal action when enabled (a diverse-but-legal
   * near-base placement by the game's growth rules); when disabled, return {@code null} so the
   * caller keeps the canonical book move.
   */
  public Action sampleOpening(List<Action> legal) {
    if (!enabled || legal == null || legal.isEmpty()) return null;
    return legal.get(random.nextInt(legal.size()));
  }
}
