package com.engine.nnue_trainer.search.gobot;

import com.engine.nnue_trainer.board.Action;
import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.board.MoveAction;
import com.engine.nnue_trainer.board.Pos;
import com.engine.nnue_trainer.nnue.BoardFeatureMapper;
import com.engine.nnue_trainer.nnue.NNUEModel;
import com.engine.nnue_trainer.search.eval.HandTunedEval;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Port of GoBot's {@code searcher} + the deterministic search entry points ({@code
 * ../virusgame/backend/search/search.go}): the transposition table, node/evaluation counters, the
 * running-budget guard, and the {@code minimax} (alpha-beta + PVS null-window re-search) / {@code
 * maxN} / {@code atDepth} / {@code ChooseDepth} search itself.
 *
 * <p>Faithful translation, not an improvement — same structure, move order, PVS windows, TT
 * probe/store semantics, and leaf evaluation ({@link HandTunedEval}, no quiescence) so a
 * fixed-depth {@link #chooseDepth} picks the same action (and same integer score) as GoBot.
 *
 * <p>Scores run in {@code long}: GoBot's {@code int} is 64-bit, and {@code infScore = 1<<60}
 * overflows a Java {@code int}. Leaf/terminal scores stay within {@code ±mateScore} (1e9) so they
 * fit an {@code int} for TT storage, but the ±∞ alpha-beta window bounds need {@code long}.
 */
public final class GoBotSearcher {

  // TT bound flags for fail-soft alpha-beta stores (GoBot's flagExact/flagLower/flagUpper iota).
  public static final int FLAG_EXACT = 0;
  public static final int FLAG_LOWER = 1;
  public static final int FLAG_UPPER = 2;

  static final int MAX_DEPTH = 64;
  static final long INF_SCORE = 1L << 60;
  static final long MATE_SCORE = 1_000_000_000L;

  /** Selectable leaf evaluation for the GoBot search (Phase 2): hand-tuned or learned NNUE. */
  public enum LeafEval {
    HAND_TUNED,
    NNUE
  }

  /**
   * NNUE output (~±1) → hand-tuned-comparable integer. The only hard constraint is staying strictly
   * within {@code ±MATE_SCORE} so a learned leaf never collides with terminal/mate scores; the
   * magnitude just needs to land in the hand-tuned band so search behaves normally.
   */
  static final long NNUE_SCALE = 1000L;

  static final long NNUE_CLAMP = MATE_SCORE - 1L; // strictly below mate

  /**
   * Immutable (mode, model) pair so newSearcher reads a consistent snapshot in one volatile read.
   */
  public static final class LeafConfig {
    final LeafEval mode;
    final NNUEModel model;

    LeafConfig(LeafEval mode, NNUEModel model) {
      this.mode = mode;
      this.model = model;
    }
  }

  // Process-wide default leaf eval, applied to each newSearcher (Task 2 sets this from env). One
  // volatile field so mode and model can never be observed torn (NNUE mode with a stale/null
  // model).
  private static volatile LeafConfig defaultLeaf = new LeafConfig(LeafEval.HAND_TUNED, null);

  final int root;
  final boolean multi;
  final Map<Long, TableEntry> table;
  LeafEval leafMode = LeafEval.HAND_TUNED;
  NNUEModel nnueModel;
  long nodes;
  long evaluations;
  long nodeLimit; // 0 == unlimited
  long deadlineMillis; // 0 == no wall-clock deadline

  GoBotSearcher(int root, boolean multi) {
    this.root = root;
    this.multi = multi;
    this.table = new HashMap<>();
  }

  /**
   * Process-wide default applied to every {@link #newSearcher} (so the static {@code chooseDepth}/
   * {@code chooseNodeBudget}/{@code choose} entry points use it). Mirrors the env/property flag
   * pattern; Task 2 wires {@code EVAL=NNUE} to call this.
   */
  public static LeafConfig configureDefaultLeafEval(LeafEval mode, NNUEModel model) {
    LeafConfig prev = defaultLeaf;
    defaultLeaf = new LeafConfig(mode, model);
    return prev;
  }

  /** Restore a previously-captured default (see {@link #configureDefaultLeafEval}). */
  public static void restoreDefaultLeafEval(LeafConfig prev) {
    defaultLeaf = prev;
  }

  /** Signals that the running budget (node limit / deadline) was exhausted mid-search. */
  private static final class SearchIncomplete extends RuntimeException {
    SearchIncomplete() {
      super(null, null, false, false);
    }
  }

  /** Port of GoBot's {@code newSearcher}: {@code multi} iff more than two players are active. */
  public static GoBotSearcher newSearcher(GoState state) {
    int activeCount = 0;
    for (int player = 1; player <= 4; player++) {
      if (state.active(player)) {
        activeCount++;
      }
    }
    GoBotSearcher s = new GoBotSearcher(state.currentPlayer(), activeCount > 2);
    LeafConfig cfg = defaultLeaf;
    s.leafMode = cfg.mode;
    s.nnueModel = cfg.model;
    return s;
  }

  /** TT probe: the stored entry for this position hash, or {@code null} on a miss. */
  public TableEntry probe(long key) {
    return table.get(key);
  }

  /** TT store: GoBot overwrites unconditionally ({@code s.table[key] = ...}). */
  public void store(long key, TableEntry entry) {
    table.put(key, entry);
  }

  /**
   * Port of GoBot's {@code running()}: stop when a node budget is exhausted or a wall-clock
   * deadline has passed. A fixed-depth {@code ChooseDepth} has neither, so it always runs to
   * completion.
   */
  public boolean running() {
    if (nodeLimit > 0 && nodes >= nodeLimit) {
      return false;
    }
    return deadlineMillis <= 0 || System.currentTimeMillis() < deadlineMillis;
  }

  // --- fixed-depth entry point (port of ChooseDepth) ---

  /**
   * One deterministic, fully completed action-depth search — GoBot's {@code ChooseDepth}, the
   * parity oracle entry point. Returns {@code null} when the depth is out of range, the position
   * has no legal action, or (for a budget-limited searcher) the search did not complete.
   */
  public static GoResult chooseDepth(GoState state, int depth) {
    if (depth < 1 || depth > MAX_DEPTH) {
      return null;
    }
    Action fallback = preservingFallback(state);
    if (fallback == null) {
      return null;
    }
    GoBotSearcher s = newSearcher(state);
    try {
      GoResult result = s.atDepth(state, depth);
      result.depth = depth;
      result.nodes = s.nodes;
      result.evaluations = s.evaluations;
      return result;
    } catch (SearchIncomplete e) {
      return null;
    }
  }

  /**
   * Convenience: build the {@link GoState} from a board + hidden state, then {@link #chooseDepth}.
   */
  public static GoResult chooseDepth(
      Board board, int player, int movesLeft, boolean[] neutralUsed, int depth) {
    return chooseDepth(GoState.fromBoard(board, player, movesLeft, neutralUsed), depth);
  }

  // --- live entry points (port of Choose / chooseNodeBudget) ---

  /** GoBot's {@code ProductionBudget}: the per-move wall-clock search budget (1s). */
  static final long PRODUCTION_BUDGET_MILLIS = 1000;

  /**
   * Port of GoBot's {@code Choose}: try the opening book, else iterative deepening bounded by a
   * wall-clock deadline (best result from the last fully completed iteration). Uses the
   * production-safe default budget of {@link #PRODUCTION_BUDGET_MILLIS}.
   */
  public static GoResult choose(GoState state) {
    return chooseWithDeadline(state, System.currentTimeMillis() + PRODUCTION_BUDGET_MILLIS);
  }

  /**
   * Port of GoBot's {@code Choose} with an explicit absolute deadline (epoch millis). Returns
   * {@code null} only when the position has no legal action.
   */
  public static GoResult chooseWithDeadline(GoState state, long deadlineMillis) {
    GoResult book = GoOpeningBook.openingBookResult(state);
    if (book != null) {
      return book;
    }
    Action fallback = preservingFallback(state);
    if (fallback == null) {
      return null;
    }
    GoResult best = new GoResult(fallback);
    GoBotSearcher s = newSearcher(state);
    s.deadlineMillis = deadlineMillis;
    for (int depth = 1; depth <= MAX_DEPTH; depth++) {
      GoResult result;
      try {
        result = s.atDepth(state, depth);
      } catch (SearchIncomplete e) {
        break;
      }
      best = result;
      best.depth = depth;
      best.nodes = s.nodes;
      best.evaluations = s.evaluations;
    }
    return best;
  }

  /**
   * Port of GoBot's {@code chooseNodeBudget}: deterministic iterative deepening bounded by a node
   * limit rather than a wall-clock deadline. Returns {@code null} when the position has no legal
   * action or {@code limit == 0}.
   */
  public static GoResult chooseNodeBudget(GoState state, long limit) {
    GoResult book = GoOpeningBook.openingBookResult(state);
    if (book != null) {
      return book;
    }
    Action fallback = preservingFallback(state);
    if (fallback == null || limit == 0) {
      return null;
    }
    GoResult best = new GoResult(fallback);
    GoBotSearcher s = newSearcher(state);
    s.nodeLimit = limit;
    for (int depth = 1; depth <= MAX_DEPTH && s.nodes < limit; depth++) {
      GoResult result;
      try {
        result = s.atDepth(state, depth);
      } catch (SearchIncomplete e) {
        break;
      }
      best = result;
      best.depth = depth;
    }
    best.nodes = s.nodes;
    best.evaluations = s.evaluations;
    best.budgetExhausted = s.nodes >= limit;
    best.searchComplete = best.depth == MAX_DEPTH;
    return best;
  }

  // --- search core ---

  private GoResult atDepth(GoState state, int depth) {
    long key = state.hash();
    TableEntry rootEntry = probe(key);
    boolean hasRoot = rootEntry != null;
    List<Child> children = orderedChildren(state, hasRoot ? rootEntry.bestAction : null, hasRoot);
    if (children.isEmpty()) {
      return new GoResult();
    }
    children = preservingChildren(children, root);

    GoResult best = new GoResult(children.get(0).action);
    best.score = (int) -INF_SCORE;
    long bestScore = -INF_SCORE;
    List<RootMove> roots = new ArrayList<>(children.size());
    long alpha = -INF_SCORE;
    long beta = INF_SCORE;
    for (int i = 0; i < children.size(); i++) {
      Child child = children.get(i);
      long score;
      if (multi) {
        long[] values = maxN(child.state, depth - 1, 1);
        score = values[root - 1];
      } else if (i == 0) {
        score = minimax(child.state, depth - 1, alpha, beta, 1);
      } else {
        // Null-window scout; re-search full window on a fail that lands inside.
        score = minimax(child.state, depth - 1, alpha, alpha + 1, 1);
        if (score > alpha && score < beta) {
          score = minimax(child.state, depth - 1, alpha, beta, 1);
        }
      }
      roots.add(new RootMove(child.action, (int) score));
      if (score > bestScore) {
        best.action = child.action;
        bestScore = score;
      }
      if (!multi && score > alpha) {
        alpha = score;
      }
    }
    best.score = (int) bestScore;
    store(key, TableEntry.single(depth, 0, FLAG_EXACT, best.action, (int) bestScore));
    best.alternatives = topAlternatives(roots, best.action);
    return best;
  }

  private long minimax(GoState state, int depth, long alpha, long beta, int ply) {
    if (!running()) {
      throw new SearchIncomplete();
    }
    nodes++;
    if (state.gameOver()) {
      return terminalScore(state, root, ply);
    }
    if (depth == 0) {
      evaluations++;
      return leafEval(state);
    }
    long key = state.hash();
    TableEntry entry = probe(key);
    boolean hit = entry != null;
    if (hit && entry.depth >= depth && entry.ply == ply) {
      switch (entry.flag) {
        case FLAG_EXACT:
          return entry.values[0];
        case FLAG_LOWER:
          if (entry.values[0] >= beta) {
            return entry.values[0];
          }
          alpha = Math.max(alpha, entry.values[0]);
          break;
        case FLAG_UPPER:
          if (entry.values[0] <= alpha) {
            return entry.values[0];
          }
          beta = Math.min(beta, entry.values[0]);
          break;
        default:
          break;
      }
      if (alpha >= beta) {
        return entry.values[0];
      }
    }
    List<Child> children = orderedChildren(state, hit ? entry.bestAction : null, hit);
    if (children.isEmpty()) {
      evaluations++;
      return leafEval(state);
    }

    long alphaOrig = alpha;
    long betaOrig = beta;
    boolean maximizing = state.currentPlayer() == root;
    long best = maximizing ? -INF_SCORE : INF_SCORE;
    Action bestAction = null;
    for (int i = 0; i < children.size(); i++) {
      Child child = children.get(i);
      long score;
      if (i == 0) {
        score = minimax(child.state, depth - 1, alpha, beta, ply + 1);
      } else if (maximizing) {
        // Null-window scout: probe whether this sibling beats alpha.
        score = minimax(child.state, depth - 1, alpha, alpha + 1, ply + 1);
        if (score > alpha && score < beta) {
          score = minimax(child.state, depth - 1, alpha, beta, ply + 1);
        }
      } else {
        score = minimax(child.state, depth - 1, beta - 1, beta, ply + 1);
        if (score < beta && score > alpha) {
          score = minimax(child.state, depth - 1, alpha, beta, ply + 1);
        }
      }
      if (maximizing) {
        if (score > best) {
          best = score;
          bestAction = child.action;
        }
        if (best > alpha) {
          alpha = best;
        }
      } else {
        if (score < best) {
          best = score;
          bestAction = child.action;
        }
        if (best < beta) {
          beta = best;
        }
      }
      if (alpha >= beta) {
        break;
      }
    }
    int flag = FLAG_EXACT;
    if (best <= alphaOrig) {
      flag = FLAG_UPPER;
    } else if (best >= betaOrig) {
      flag = FLAG_LOWER;
    }
    store(key, TableEntry.single(depth, ply, flag, bestAction, (int) best));
    return best;
  }

  private long[] maxN(GoState state, int depth, int ply) {
    if (!running()) {
      throw new SearchIncomplete();
    }
    nodes++;
    if (state.gameOver()) {
      return terminalScores(state, ply);
    }
    if (depth == 0) {
      evaluations++;
      return leafEvalAll(state);
    }
    long key = state.hash();
    TableEntry entry = probe(key);
    boolean hit = entry != null;
    if (hit && entry.depth >= depth && entry.ply == ply) {
      return toLong(entry.values);
    }
    List<Child> children = orderedChildren(state, hit ? entry.bestAction : null, hit);
    if (children.isEmpty()) {
      evaluations++;
      return leafEvalAll(state);
    }

    int player = state.currentPlayer();
    // maxBound is the best any child can return for the mover: an immediate terminal win.
    long maxBound = MATE_SCORE - (ply + 1);
    long[] best = new long[4];
    best[player - 1] = -INF_SCORE;
    Action bestAction = null;
    for (Child child : children) {
      long[] values = maxN(child.state, depth - 1, ply + 1);
      if (values[player - 1] > best[player - 1]) {
        best = values;
        bestAction = child.action;
        if (best[player - 1] >= maxBound) {
          break;
        }
      }
    }
    store(key, new TableEntry(depth, ply, FLAG_EXACT, bestAction, toInt(best)));
    return best;
  }

  // --- move generation / ordering (port of orderedChildren + preservingChildren + fallback) ---

  private static final class Child {
    final Action action;
    final GoState state;
    final int order;

    Child(Action action, GoState state, int order) {
      this.action = action;
      this.state = state;
      this.order = order;
    }
  }

  private List<Child> orderedChildren(GoState state, Action ttMove, boolean hasTT) {
    GoPosition pos = GoPosition.of(state);
    int actor = state.currentPlayer();
    int beforeActive = activeCount(state);
    List<Child> children = new ArrayList<>();
    for (Action action : pos.searchActions()) {
      if (!running()) {
        throw new SearchIncomplete();
      }
      GoState next = pos.applySearch(action);
      int order = 0;
      if (hasTT && action.equals(ttMove)) {
        order += 10_000_000;
      }
      if (next.gameOver() && next.winner() == actor) {
        order += 1_000_000;
      }
      order += (beforeActive - activeCount(next)) * 100_000;
      if (action instanceof MoveAction) {
        Pos t = ((MoveAction) action).target;
        Cell target = state.at(t.row, t.col);
        if (target.kind == CellKind.NORMAL && target.owner != actor) {
          order += 10_000;
        }
      }
      if (next.currentPlayer() == actor) {
        order += 100;
      }
      children.add(new Child(action, next, order));
    }
    // Stable descending sort by order (Go's sort.SliceStable); ties keep board order.
    children.sort(Comparator.comparingInt((Child c) -> c.order).reversed());
    return children;
  }

  private static List<Child> preservingChildren(List<Child> children, int actor) {
    boolean anySurvives = false;
    for (Child c : children) {
      if (c.state.active(actor)) {
        anySurvives = true;
        break;
      }
    }
    if (!anySurvives) {
      return children;
    }
    List<Child> kept = new ArrayList<>();
    for (Child c : children) {
      if (c.state.active(actor)) {
        kept.add(c);
      }
    }
    return kept;
  }

  /**
   * Port of {@code preservingFallback}: a legal action that does not immediately eliminate the
   * actor if one exists, else the first legal action. {@code null} when no action is legal.
   */
  private static Action preservingFallback(GoState state) {
    List<Action> actions = state.legalActions();
    if (actions.isEmpty()) {
      return null;
    }
    int actor = state.currentPlayer();
    for (Action action : actions) {
      GoState next = state.apply(action);
      if (next != null && next.active(actor)) {
        return action;
      }
    }
    return actions.get(0);
  }

  // --- scoring helpers (port of terminalScore / evaluate / activeCount) ---

  private long leafEval(GoState state) {
    if (leafMode == LeafEval.NNUE) {
      return nnueLeaf(state.toBoard(), root, nnueModel);
    }
    return HandTunedEval.staticEval(
        state.toBoard(), root, state.currentPlayer(), state.movesLeft(), state.neutralUsed);
  }

  private long[] leafEvalAll(GoState state) {
    Board board = state.toBoard();
    long[] all = new long[4];
    // ponytail: NNUE feature map is 2-player (opponent = 3 - player); for players 3/4 it maps only
    // own stones. The clean 2-player test bench (Java vs GoBot) never hits maxN, so this is fine.
    NNUEModel model = leafMode == LeafEval.NNUE ? nnueModel : null;
    for (int p = 1; p <= 4; p++) {
      all[p - 1] =
          model != null
              ? nnueLeaf(board, p, model)
              : HandTunedEval.staticEval(
                  board, p, state.currentPlayer(), state.movesLeft(), state.neutralUsed);
    }
    return all;
  }

  /**
   * NNUE leaf value oriented to {@code player} (higher = better for {@code player}, matching {@link
   * HandTunedEval}): {@code round(forward(features) * NNUE_SCALE)} clamped strictly inside {@code
   * ±MATE_SCORE} so it never collides with terminal/mate scores.
   */
  static long nnueLeaf(Board board, int player, NNUEModel model) {
    double value = model.forward(BoardFeatureMapper.map(board, player));
    long scaled = Math.round(value * NNUE_SCALE);
    return Math.max(-NNUE_CLAMP, Math.min(NNUE_CLAMP, scaled));
  }

  /**
   * Inverse of the NNUE leaf scaling: a backed-up search score → the net's output range, clamped to
   * ±1. Used as the TD-leaf target when self-play drives move selection through this search (Phase
   * 2 Task 3). Terminal/mate magnitudes (~1e9) collapse to ±1.
   */
  public static float scoreToUnit(long score) {
    double v = (double) score / NNUE_SCALE;
    return (float) Math.max(-1.0, Math.min(1.0, v));
  }

  private static long terminalScore(GoState state, int player, int ply) {
    if (state.winner() == player) {
      return MATE_SCORE - ply;
    }
    return -MATE_SCORE + ply;
  }

  private static long[] terminalScores(GoState state, int ply) {
    long[] scores = new long[4];
    for (int player = 1; player <= 4; player++) {
      scores[player - 1] = terminalScore(state, player, ply);
    }
    return scores;
  }

  private static int activeCount(GoState state) {
    int count = 0;
    for (int player = 1; player <= 4; player++) {
      if (state.active(player)) {
        count++;
      }
    }
    return count;
  }

  private static long[] toLong(int[] values) {
    long[] out = new long[4];
    for (int i = 0; i < 4; i++) {
      out[i] = values[i];
    }
    return out;
  }

  private static int[] toInt(long[] values) {
    int[] out = new int[4];
    for (int i = 0; i < 4; i++) {
      out[i] = (int) values[i];
    }
    return out;
  }

  private static final int MAX_ALTERNATIVES = 4;

  /** Best next-best root moves (excluding chosen), best-first, capped — diagnostics only. */
  private static List<RootMove> topAlternatives(List<RootMove> roots, Action chosen) {
    if (roots.size() <= 1) {
      return null;
    }
    List<RootMove> sorted = new ArrayList<>(roots);
    sorted.sort(Comparator.comparingInt((RootMove rm) -> rm.score).reversed());
    List<RootMove> alts = new ArrayList<>(MAX_ALTERNATIVES);
    for (RootMove rm : sorted) {
      if (rm.action.equals(chosen)) {
        continue;
      }
      alts.add(rm);
      if (alts.size() == MAX_ALTERNATIVES) {
        break;
      }
    }
    return alts.isEmpty() ? null : alts;
  }
}
