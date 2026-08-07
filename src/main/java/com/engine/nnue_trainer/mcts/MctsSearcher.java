package com.engine.nnue_trainer.mcts;

import com.engine.nnue_trainer.board.Action;
import com.engine.nnue_trainer.search.eval.HandTunedEval;
import com.engine.nnue_trainer.search.gobot.GoState;
import java.util.List;
import java.util.Random;

/**
 * PUCT/MCTS searcher over {@link GoState} — Phase 1 of the AlphaZero feasibility plan ({@code
 * docs/plans/20260807-mcts-az-feasibility.md}, D1).
 *
 * <p><b>Per-action nodes:</b> each node is a full {@code GoState} (grid + {@code movesLeft} +
 * {@code neutralUsed} + {@code current}); children are single actions, so branching stays ~34 and
 * consecutive tree plies do <b>not</b> alternate players (47.1% of edges flip the mover).
 *
 * <p><b>Absolute-frame backup (the v3 frame bug, solved structurally):</b> because edges may or may
 * not flip the mover, negamax-style "negate on every backup" is wrong on the 53% of edges that keep
 * the mover. Instead every value is converted at the leaf into a fixed absolute frame
 * (positive-is-good-for-player-1): {@code v_abs = (leaf mover == 1) ? v : -v}, terminals are {@code
 * +1/-1/0} from {@link GoState#outcomeWinner()}. Nodes accumulate {@code W} in that frame with
 * <b>no per-edge negation anywhere</b>; selection at node {@code n} maximizes {@code sign(n) *
 * Q_abs(child) + U} where {@code sign(n) = +1} iff {@code n}'s mover is player 1.
 *
 * <p>Leaf value is {@code tanh(HandTunedEval / valueScale)} queried from the leaf's <b>own</b>
 * {@code currentPlayer()} (in distribution — same rationale as {@code GoBotSearcher.leafEval}).
 * Priors are pluggable ({@link PolicyPrior}); Dirichlet root noise exists for later self-play but
 * is OFF by default (play mode).
 */
public final class MctsSearcher {

  /** Tuning knobs; the defaults are the Phase 1 play-mode configuration. */
  public static final class Config {
    public double cpuct = 1.5;
    public long seed = 1L;

    /**
     * Value squash: {@code v = tanh(handTuned / valueScale)}. 12000 maps a typical decisive
     * mid-game eval (~13k, see docs/nnue-v3-net.md sibling stats) to ~0.8 per the plan's
     * calibration target; terminal-adjacent evals (~5e8) saturate to ±1.
     */
    public double valueScale = 12000.0;

    public PolicyPrior prior = PolicyPrior.UNIFORM;

    /**
     * Trained value head ({@code MCTS_VALUE=net}): mover-frame tanh value from the artifact's
     * {@code value_head}; {@code null} falls back to the hand-tuned leaf above.
     */
    public PolicyNetPrior valueNet = null;

    /** Dirichlet root noise — self-play exploration only; OFF in play mode. */
    public boolean rootNoise = false;

    public double noiseAlpha = 0.3;
    public double noiseEpsilon = 0.25;
  }

  static final class Node {
    final GoState state;
    final int mover; // state.currentPlayer() at this node
    boolean terminal;
    double terminalValueAbs;
    Action[] actions; // null until expanded
    float[] prior;
    Node[] children;
    int[] n;
    double[] w; // absolute-frame value sums — positive is good for player 1
    int visits; // sum of edge visits below this node

    Node(GoState state) {
      this.state = state;
      this.mover = state.currentPlayer();
      if (state.gameOver()) {
        terminal = true;
        terminalValueAbs = terminalValueAbs(state);
      }
    }
  }

  private final Config config;
  private final Random random;
  private final Node root;
  private int sims;
  private Node[] pathNodes = new Node[64];
  private int[] pathEdges = new int[64];

  public MctsSearcher(GoState state, Config config) {
    this.config = config;
    this.random = new Random(config.seed);
    this.root = new Node(state);
    if (!root.terminal) {
      expand(root);
      if (!root.terminal && config.rootNoise) {
        applyRootNoise();
      }
    }
  }

  /** Sim-budgeted search: run exactly {@code count} additional simulations. */
  public void runSims(int count) {
    if (root.terminal) {
      return;
    }
    for (int i = 0; i < count; i++) {
      simulateOnce();
    }
  }

  /** Time-budgeted search: simulate until the absolute epoch-millis deadline (≥1 sim). */
  public void runUntilDeadline(long deadlineEpochMillis) {
    if (root.terminal) {
      return;
    }
    do {
      simulateOnce();
    } while (System.currentTimeMillis() < deadlineEpochMillis);
  }

  /** Most-visited root action (play mode: argmax visits, ties by board order), or null. */
  public Action bestAction() {
    if (root.terminal || root.actions == null) {
      return null;
    }
    int best = 0;
    for (int a = 1; a < root.actions.length; a++) {
      if (root.n[a] > root.n[best]) {
        best = a;
      }
    }
    return root.actions[best];
  }

  public int simsRun() {
    return sims;
  }

  /** Root value estimate in the absolute frame (mean of all backed-up values). */
  public double rootValueAbs() {
    if (root.terminal) {
      return root.terminalValueAbs;
    }
    double sum = 0;
    for (int a = 0; a < root.w.length; a++) {
      sum += root.w[a];
    }
    return root.visits == 0 ? 0 : sum / root.visits;
  }

  /** One PUCT search from the position, sim budget. */
  public static Action chooseSims(GoState state, int simBudget, Config config) {
    MctsSearcher s = new MctsSearcher(state, config);
    s.runSims(simBudget);
    return s.bestAction();
  }

  /** One PUCT search from the position, wall-clock budget (absolute epoch-millis deadline). */
  public static Action chooseDeadline(GoState state, long deadlineEpochMillis, Config config) {
    MctsSearcher s = new MctsSearcher(state, config);
    s.runUntilDeadline(deadlineEpochMillis);
    return s.bestAction();
  }

  // --- core ---

  private void simulateOnce() {
    Node node = root;
    int depth = 0;
    double vAbs;
    while (true) {
      if (node.terminal) {
        vAbs = node.terminalValueAbs;
        break;
      }
      if (node.actions == null) {
        expand(node);
        vAbs = node.terminal ? node.terminalValueAbs : leafValue(node.state);
        break;
      }
      int a = select(node);
      Node child = node.children[a];
      if (child == null) {
        child = new Node(node.state.applyGenerated(node.actions[a]));
        node.children[a] = child;
      }
      if (depth == pathNodes.length) {
        pathNodes = java.util.Arrays.copyOf(pathNodes, depth * 2);
        pathEdges = java.util.Arrays.copyOf(pathEdges, depth * 2);
      }
      pathNodes[depth] = node;
      pathEdges[depth] = a;
      depth++;
      node = child;
    }
    // Backup in the absolute frame: no negation, ever (D1's value-frame invariant).
    for (int i = 0; i < depth; i++) {
      Node p = pathNodes[i];
      p.visits++;
      p.n[pathEdges[i]]++;
      p.w[pathEdges[i]] += vAbs;
    }
    sims++;
  }

  private void expand(Node node) {
    List<Action> legal = node.state.legalActions();
    if (legal.isEmpty()) {
      // Stuck without gameOver (snapshot roots): score it by the real outcome rule.
      node.terminal = true;
      node.terminalValueAbs = terminalValueAbs(node.state);
      return;
    }
    node.actions = legal.toArray(new Action[0]);
    node.prior = config.prior.priors(node.state, legal);
    node.children = new Node[node.actions.length];
    node.n = new int[node.actions.length];
    node.w = new double[node.actions.length];
  }

  /**
   * PUCT selection at {@code node}: argmax over children of {@code sign(node) * Q_abs + U}. The
   * sign converts the absolute-frame Q to the mover's frame at selection time — this, not backup
   * negation, is where the mover matters.
   */
  int select(Node node) {
    int sign = node.mover == 1 ? 1 : -1;
    double sqrtN = Math.sqrt(node.visits + 1.0);
    int best = 0;
    double bestScore = Double.NEGATIVE_INFINITY;
    for (int a = 0; a < node.actions.length; a++) {
      double q = node.n[a] > 0 ? sign * node.w[a] / node.n[a] : 0.0;
      double u = config.cpuct * node.prior[a] * sqrtN / (1 + node.n[a]);
      double score = q + u;
      if (score > bestScore) {
        bestScore = score;
        best = a;
      }
    }
    return best;
  }

  /**
   * Leaf value in the absolute frame: the trained value head when configured, else the hand-tuned
   * eval. Both are queried in the leaf's own mover frame and fixed to positive-is-good-for-player-1
   * with the mover sign — the single flip point (the v3 lesson).
   */
  private double leafValue(GoState state) {
    if (config.valueNet == null) {
      return leafValueAbs(state, config.valueScale);
    }
    double v = config.valueNet.valueMover(state);
    // ponytail: this re-runs the trunk the prior already ran at expansion; fuse the two
    // forwards if self-play throughput misses G2's 300 games/h bar.
    return state.currentPlayer() == 1 ? v : -v;
  }

  /**
   * Leaf value in the absolute frame: hand-tuned eval queried from the leaf's own mover (its
   * natural frame), tanh-squashed, then fixed to positive-is-good-for-player-1.
   */
  static double leafValueAbs(GoState state, double valueScale) {
    int mover = state.currentPlayer();
    boolean[] nu = new boolean[4];
    nu[0] = state.neutralUsed(1);
    nu[1] = state.neutralUsed(2);
    int ht = HandTunedEval.staticEval(state.toBoard(), mover, state.movesLeft(), nu);
    double v = Math.tanh(ht / valueScale);
    return mover == 1 ? v : -v;
  }

  /** Terminal value in the absolute frame from the single labeling rule, incl. territory tie. */
  static double terminalValueAbs(GoState state) {
    int winner = state.outcomeWinner();
    return winner == 1 ? 1.0 : winner == 2 ? -1.0 : 0.0;
  }

  Node root() {
    return root;
  }

  // --- Dirichlet root noise (self-play exploration; OFF by default) ---

  private void applyRootNoise() {
    int k = root.prior.length;
    double[] g = new double[k];
    double sum = 0;
    for (int i = 0; i < k; i++) {
      g[i] = gammaSample(config.noiseAlpha);
      sum += g[i];
    }
    if (sum <= 0) {
      return;
    }
    for (int i = 0; i < k; i++) {
      root.prior[i] =
          (float) ((1 - config.noiseEpsilon) * root.prior[i] + config.noiseEpsilon * (g[i] / sum));
    }
  }

  /** Marsaglia–Tsang gamma sampler (shape-only; scale is irrelevant for a Dirichlet). */
  private double gammaSample(double alpha) {
    if (alpha < 1) {
      // Boost: gamma(a) = gamma(a+1) * U^(1/a)
      return gammaSample(alpha + 1) * Math.pow(random.nextDouble(), 1.0 / alpha);
    }
    double d = alpha - 1.0 / 3.0;
    double c = 1.0 / Math.sqrt(9 * d);
    while (true) {
      double x = random.nextGaussian();
      double v = 1 + c * x;
      if (v <= 0) {
        continue;
      }
      v = v * v * v;
      double u = random.nextDouble();
      if (u < 1 - 0.0331 * x * x * x * x || Math.log(u) < 0.5 * x * x + d * (1 - v + Math.log(v))) {
        return d * v;
      }
    }
  }
}
