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
import com.engine.nnue_trainer.search.ordering.PolicyOrderingTable;
import com.engine.nnue_trainer.v2.NNUEv2Evaluator;
import com.engine.nnue_trainer.v3.NNUEv3Accumulator;
import com.engine.nnue_trainer.v3.V3Eval;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    NNUE,
    NNUEV2,
    NNUEV3
  }

  /**
   * NNUE output (~±1) → hand-tuned-comparable integer. The only hard constraint is staying strictly
   * within {@code ±MATE_SCORE} so a learned leaf never collides with terminal/mate scores; the
   * magnitude just needs to land in the hand-tuned band so search behaves normally.
   */
  static final long NNUE_SCALE = 1000L;

  static final long NNUE_CLAMP = MATE_SCORE - 1L; // strictly below mate

  /**
   * NNUE v2 output is a WDL-ish scalar in the side-to-move frame (~[0,1], 0.5 = even, higher =
   * better-for-mover). Map {@code (wdl - 0.5) * SCALE} so evals span a hand-tuned-comparable band
   * (a few thousand) while staying inside {@code ±MATE_SCORE}. Value is a calibration knob — only
   * the ordering matters for fixed-depth play.
   */
  static final long NNUEV2_SCALE = scaleFromEnv();

  /** WDL-&gt;score scale: env {@code NNUEV2_SCALE} (diagnostic sweep knob), default 4000. */
  private static long scaleFromEnv() {
    String s = System.getenv("NNUEV2_SCALE");
    if (s != null && !s.isBlank()) {
      try {
        return Long.parseLong(s.trim());
      } catch (NumberFormatException ignored) {
        // fall through to default
      }
    }
    return 4000L;
  }

  /**
   * Immutable (mode, model) snapshot so newSearcher reads a consistent view in one volatile read.
   * At most one of {@code model}/{@code v2}/{@code v3} is non-null (matching {@code mode}).
   */
  public static final class LeafConfig {
    final LeafEval mode;
    final NNUEModel model;
    final NNUEv2Evaluator v2;
    final V3Eval v3;

    LeafConfig(LeafEval mode, NNUEModel model) {
      this(mode, model, null, null);
    }

    LeafConfig(LeafEval mode, NNUEModel model, NNUEv2Evaluator v2) {
      this(mode, model, v2, null);
    }

    LeafConfig(LeafEval mode, NNUEModel model, NNUEv2Evaluator v2, V3Eval v3) {
      this.mode = mode;
      this.model = model;
      this.v2 = v2;
      this.v3 = v3;
    }
  }

  // Process-wide default leaf eval, applied to each newSearcher (Task 2 sets this from env). One
  // volatile field so mode and model can never be observed torn (NNUE mode with a stale/null
  // model).
  private static volatile LeafConfig defaultLeaf = new LeafConfig(LeafEval.HAND_TUNED, null);

  /**
   * Neural quiet-move ordering (epic 1jh integration). Loaded once per process; immutable after
   * load, so sharing across searchers/threads is safe. Null = off = pre-table ordering — the env
   * {@code ORDERING_TABLE} overrides the default path, a missing artifact silently disables, and a
   * present-but-corrupt artifact warns and disables (the searcher must never fail to move because
   * an ordering hint is bad). Enhanced path only; the {@code chooseDepth} parity oracle never
   * consults it.
   */
  private static volatile PolicyOrderingTable orderingTable = loadOrderingTable();

  private static PolicyOrderingTable loadOrderingTable() {
    String env = System.getenv("ORDERING_TABLE");
    Path path =
        env == null || env.isBlank() ? PolicyOrderingTable.DEFAULT_WEIGHTS : Path.of(env.trim());
    if (!Files.isRegularFile(path)) {
      return null;
    }
    try {
      return PolicyOrderingTable.load(path);
    } catch (IOException e) {
      System.err.println("ordering table disabled, load failed (" + path + "): " + e.getMessage());
      return null;
    }
  }

  /** Test hook: swap the process-wide ordering table, returning the previous one for restore. */
  static PolicyOrderingTable setOrderingTable(PolicyOrderingTable t) {
    PolicyOrderingTable prev = orderingTable;
    orderingTable = t;
    return prev;
  }

  final int root;
  final boolean multi;
  final Map<Long, TableEntry> table;

  /**
   * Strength-path switch (plan: parity constraint). True for the live/gauntlet entry points ({@code
   * chooseNodeBudget}/{@code chooseWithDeadline} and the instance {@code search*} methods): packed
   * array TT with depth-sufficient cross-ply probing, persistence across moves. False for the
   * {@code chooseDepth} parity oracle, which keeps GoBot's exact HashMap TT and ply-exact probe so
   * GoBotSearchParityTest stays byte-identical.
   */
  final boolean enhanced;

  final GoTranspositionTable tt; // enhanced minimax only; maxN + parity path keep `table`
  LeafEval leafMode = LeafEval.HAND_TUNED;
  NNUEModel nnueModel;
  NNUEv2Evaluator nnueV2;
  V3Eval nnueV3;
  long nodes;
  long evaluations;
  long nodeLimit; // 0 == unlimited
  long deadlineMillis; // 0 == no wall-clock deadline
  long fastPathCuts; // diagnostics: cut-nodes that never materialized their sibling list
  long ttProbes; // diagnostics: enhanced-TT probes
  long ttHits; // diagnostics: enhanced-TT hits (any depth)
  long cutCount; // diagnostics: interior-node beta cutoffs (fast-path cuts included, index 0)
  long cutIndexSum; // diagnostics: sum of searched-child index at cutoff (mean = ordering quality)
  long aspirationFailLows; // diagnostics: aspirated iterations that failed low (re-searched)
  long aspirationFailHighs; // diagnostics: aspirated iterations that failed high (re-searched)

  // Plan item 6 rollback/A-B flag: DEFAULT ON on the enhanced path; env GOBOT_ASPIRATION=0
  // disables (baked at class load; tests use the override). The parity oracle never sees it —
  // every use is additionally gated on `enhanced`.
  static final boolean ASPIRATION_ENV = !"0".equals(System.getenv("GOBOT_ASPIRATION"));
  static Boolean aspirationOverride; // test hook, like smpThreadsOverride

  static boolean aspirationEnabled() {
    Boolean o = aspirationOverride;
    return o != null ? o : ASPIRATION_ENV;
  }

  // Plan item 3 (enhanced only): two killer actions per ply, recorded on quiet-move cutoffs and
  // ordered right after the TT move; plus a per-mover, per-cell history table bumped depth^2 on
  // quiet cutoffs, halved each new search. Both are per-searcher — no cross-searcher bleed (lazy
  // SMP helpers are separate searcher instances, so they get their own killers/history for free).
  final Action[][] killers = new Action[MAX_DEPTH + 1][2];
  int[][] history; // [mover-1][cell index], lazily sized to the board; 1v1 movers only

  // Lazy SMP (plan item 4): set by the owning main searcher to abort a helper; checked in
  // running(). Never set on a main searcher, so single-thread semantics are untouched.
  volatile boolean stopRequested;
  Throwable helperFailure; // a helper's escaped exception, surfaced to tests; main ignores it
  List<GoBotSearcher> lastHelpers; // helpers of the most recent search, for tests/diagnostics

  GoBotSearcher(int root, boolean multi) {
    this(root, multi, false);
  }

  GoBotSearcher(int root, boolean multi, boolean enhanced) {
    this(root, multi, enhanced, null);
  }

  /** {@code sharedTT != null} builds a lazy-SMP helper probing/storing the owner's table. */
  GoBotSearcher(int root, boolean multi, boolean enhanced, GoTranspositionTable sharedTT) {
    this.root = root;
    this.multi = multi;
    this.enhanced = enhanced;
    this.table = new HashMap<>();
    this.tt = enhanced ? (sharedTT != null ? sharedTT : new GoTranspositionTable()) : null;
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

  /**
   * As above, for the NNUE v2 leaf ({@code mode} is normally {@link LeafEval#NNUEV2}). Distinct
   * name (not an overload) so existing {@code configureDefaultLeafEval(mode, null)} callers stay
   * unambiguous.
   */
  public static LeafConfig configureDefaultLeafEvalV2(LeafEval mode, NNUEv2Evaluator v2) {
    LeafConfig prev = defaultLeaf;
    defaultLeaf = new LeafConfig(mode, null, v2);
    return prev;
  }

  /**
   * As above, for the NNUE v3 leaf ({@code mode} is normally {@link LeafEval#NNUEV3}). Distinct
   * name (not an overload) for the same reason as the v2 variant.
   */
  public static LeafConfig configureDefaultLeafEvalV3(LeafEval mode, V3Eval v3) {
    LeafConfig prev = defaultLeaf;
    defaultLeaf = new LeafConfig(mode, null, null, v3);
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

  // --- lazy SMP (plan item 4) ---

  /**
   * Lazy-SMP width: {@code min(availableProcessors, GOBOT_SMP_THREADS)} with a conservative default
   * cap of 4 (the machine also runs self-play/gauntlets); 0 or 1 = off. N-1 helper threads run the
   * same iterative deepening from the same root on the SHARED packed TT at staggered start depths
   * (2/3 alternating, the classic skew); the main thread's result is authoritative and helpers are
   * aborted the moment it returns. Helpers keep their own killers/history/counters, so the only
   * shared structure is the XOR-verified TT.
   *
   * <p>Node accounting stays the main thread's own {@code nodes} — SMP-off budget semantics are
   * byte-identical to single-threaded search. With SMP on, helper TT entries steer the main tree,
   * so node-budget results are NOT reproducible run-to-run: fixed-node gauntlet gates should run
   * with {@code GOBOT_SMP_THREADS=0} (or use time mode, where nondeterminism is inherent anyway).
   */
  static final int SMP_THREADS = smpThreadsFromEnv();

  /** Test hook: overrides {@link #SMP_THREADS} when non-null (env is baked at class load). */
  static Integer smpThreadsOverride;

  private static int smpThreadsFromEnv() {
    int cap = 4;
    String s = System.getenv("GOBOT_SMP_THREADS");
    if (s != null && !s.isBlank()) {
      try {
        cap = Integer.parseInt(s.trim());
      } catch (NumberFormatException ignored) {
        // keep default
      }
    }
    return Math.max(0, Math.min(cap, Runtime.getRuntime().availableProcessors()));
  }

  private static int smpThreads() {
    Integer override = smpThreadsOverride;
    return override != null ? override : SMP_THREADS;
  }

  /**
   * Starts the N-1 lazy-SMP helpers for a search of {@code state}, or returns an empty list when
   * SMP is off (or this is the parity path / a 3+-player game, which never runs SMP).
   */
  private List<Thread> startHelpers(GoState state) {
    int n = enhanced && !multi ? smpThreads() : 0;
    lastHelpers = null;
    if (n <= 1) {
      return List.of();
    }
    List<GoBotSearcher> spawned = new ArrayList<>(n - 1);
    List<Thread> threads = new ArrayList<>(n - 1);
    for (int i = 0; i < n - 1; i++) {
      GoBotSearcher h = new GoBotSearcher(root, multi, true, tt);
      h.leafMode = leafMode;
      h.nnueModel = nnueModel;
      h.nnueV2 = nnueV2;
      h.nnueV3 = nnueV3;
      int startDepth = 2 + (i & 1); // classic lazy-SMP stagger: depth+1 / depth+2 alternating
      Thread t = new Thread(() -> h.helperLoop(state, startDepth), "gobot-smp-" + i);
      t.setDaemon(true);
      spawned.add(h);
      threads.add(t);
      t.start();
    }
    lastHelpers = spawned;
    return threads;
  }

  /** Aborts and joins the helpers; the main thread's completed result is already in hand. */
  private void stopHelpers(List<Thread> threads) {
    if (lastHelpers != null) {
      for (GoBotSearcher h : lastHelpers) {
        h.stopRequested = true;
      }
    }
    for (Thread t : threads) {
      try {
        t.join();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  /**
   * A helper's whole life: iterative deepening from {@code startDepth} on the shared TT until
   * aborted (or MAX_DEPTH). Results are discarded — a helper's only output is the TT entries it
   * leaves behind for the main thread. No generation bump (the main searcher owns the clock), no
   * budget: {@link #stopRequested} is the sole terminator.
   */
  private void helperLoop(GoState state, int startDepth) {
    history = new int[2][state.rows() * state.cols()];
    try {
      for (int depth = startDepth; depth <= MAX_DEPTH; depth++) {
        // Helpers keep the FULL window (no aspiration): their scores are discarded, so there is
        // no trusted center to aspirate around (they skip depths, and a stale center would just
        // be re-search churn), and full-window bounds in the shared TT stay valid for whatever
        // window the main thread happens to search. Simplest correct.
        atDepth(state, depth);
      }
    } catch (SearchIncomplete expected) {
      // aborted by the main thread (or a shared-deadline race) — normal helper death
    } catch (Throwable t) {
      helperFailure = t; // never let a helper take down the move choice; tests assert null
    }
  }

  /** Port of GoBot's {@code newSearcher}: {@code multi} iff more than two players are active. */
  public static GoBotSearcher newSearcher(GoState state) {
    return newSearcher(state, false);
  }

  /**
   * A persistent, enhanced searcher rooted at {@code state.currentPlayer()}. Keep it for the whole
   * game and call {@link #searchNodeBudget}/{@link #searchWithDeadline} each move — the packed TT
   * carries the previous move's most-searched subtree into every new search. One instance per side:
   * root-relative scores are only valid while the root player matches the mover.
   */
  public static GoBotSearcher newEnhancedSearcher(GoState state) {
    return newSearcher(state, true);
  }

  static GoBotSearcher newSearcher(GoState state, boolean enhanced) {
    int activeCount = 0;
    for (int player = 1; player <= 4; player++) {
      if (state.active(player)) {
        activeCount++;
      }
    }
    GoBotSearcher s = new GoBotSearcher(state.currentPlayer(), activeCount > 2, enhanced);
    LeafConfig cfg = defaultLeaf;
    s.leafMode = cfg.mode;
    s.nnueModel = cfg.model;
    s.nnueV2 = cfg.v2;
    s.nnueV3 = cfg.v3;
    return s;
  }

  /** The player this searcher evaluates for (fixed at creation; see persistence contract). */
  public int rootPlayer() {
    return root;
  }

  /** Enhanced-TT presence probe for tests/diagnostics. */
  boolean ttHasEntry(GoState state) {
    return enhanced && tt.probe(state.hash()) != 0L;
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
    if (stopRequested) {
      return false; // lazy-SMP helper abort; never set on a main searcher
    }
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
   *
   * <p>Contract (bd 0dj.7, enforced by {@code GoBotChooseDeadlineConsistencyTest}): the returned
   * move is exactly the move of the deepest FULLY COMPLETED iteration of the same (enhanced)
   * iterative deepening — a deadline abort mid-iteration throws {@link SearchIncomplete} out of
   * {@link #atDepth} before anything is committed, so a partially searched iteration can never
   * override the last complete one. If not even depth 1 completes, the {@code preservingFallback}
   * action is returned with {@code depth == 0}. This is the strength path (enhanced TT); the
   * GoBot-exact behavior remains on {@link #chooseDepth}.
   */
  public static GoResult chooseWithDeadline(GoState state, long deadlineMillis) {
    return newSearcher(state, true).searchWithDeadline(state, deadlineMillis);
  }

  /**
   * Port of GoBot's {@code chooseNodeBudget}: deterministic iterative deepening bounded by a node
   * limit rather than a wall-clock deadline. Returns {@code null} when the position has no legal
   * action or {@code limit == 0}. This is the strength path — enhanced (packed TT, cross-ply
   * probing); the byte-exact GoBot behavior lives on in the package-visible 3-arg overload with
   * {@code enhanced == false} (used by GoBotNodeBudgetParityTest).
   */
  public static GoResult chooseNodeBudget(GoState state, long limit) {
    return chooseNodeBudget(state, limit, true);
  }

  static GoResult chooseNodeBudget(GoState state, long limit, boolean enhanced) {
    return newSearcher(state, enhanced).searchNodeBudget(state, limit);
  }

  /** Instance {@link #choose}: production budget on a persistent (usually enhanced) searcher. */
  public GoResult search(GoState state) {
    return searchWithDeadline(state, System.currentTimeMillis() + PRODUCTION_BUDGET_MILLIS);
  }

  /** Soft deadline (plan item 5): don't start iteration d+1 past this share of the budget. */
  static final int SOFT_DEADLINE_PERCENT = 55;

  /**
   * Instance {@link #chooseWithDeadline}. On an enhanced searcher this is the persistent entry
   * point: the TT survives between calls (generation-aged, not cleared), so each move starts warm
   * from the previous move's principal subtree.
   *
   * <p>Enhanced time management (plan item 5): a new iteration costs roughly EBF times the last
   * one, so past {@link #SOFT_DEADLINE_PERCENT} of the budget it would almost surely be cut — don't
   * start it. And when the deadline does abort an iteration whose PV child (child 0, the previous
   * best) completed, the aborted iteration's best fully-searched root move is salvaged instead of
   * discarded ({@link GoResult#salvaged}) — root best-move updates only ever happen on exact
   * re-searched scores (the root alpha equals the running best), so a fail-low bound can never
   * displace the PV move.
   */
  public GoResult searchWithDeadline(GoState state, long deadlineMillis) {
    GoResult book = GoOpeningBook.openingBookResult(state);
    if (book != null) {
      return book;
    }
    Action fallback = preservingFallback(state);
    if (fallback == null) {
      return null;
    }
    long startMillis = System.currentTimeMillis();
    long budget = deadlineMillis - startMillis;
    beginSearch(state, deadlineMillis, 0);
    GoResult best = new GoResult(fallback);
    List<Thread> helpers = startHelpers(state);
    long prevScore = 0;
    boolean hasPrev = false;
    try {
      for (int depth = 1; depth <= MAX_DEPTH; depth++) {
        if (enhanced
            && depth > 1
            && budget > 0
            && (System.currentTimeMillis() - startMillis) * 100 > budget * SOFT_DEADLINE_PERCENT) {
          break; // soft deadline: recover the half-budget a doomed iteration would waste
        }
        GoResult result;
        try {
          result = atDepthAspirated(state, depth, hasPrev, prevScore);
        } catch (SearchIncomplete e) {
          if (enhanced && partialRoot != null && partialRoot.action != null) {
            partialRoot.depth = best.depth; // the label stays the last COMPLETED iteration
            partialRoot.nodes = nodes;
            partialRoot.evaluations = evaluations;
            best = partialRoot;
          }
          break;
        }
        best = result;
        best.depth = depth;
        best.nodes = nodes;
        best.evaluations = evaluations;
        prevScore = result.score;
        hasPrev = true;
      }
    } finally {
      stopHelpers(helpers);
    }
    return best;
  }

  /** Instance {@link #chooseNodeBudget(GoState, long)}; see {@link #searchWithDeadline}. */
  public GoResult searchNodeBudget(GoState state, long limit) {
    GoResult book = GoOpeningBook.openingBookResult(state);
    if (book != null) {
      return book;
    }
    Action fallback = preservingFallback(state);
    if (fallback == null || limit == 0) {
      return null;
    }
    beginSearch(state, 0, limit);
    GoResult best = new GoResult(fallback);
    List<Thread> helpers = startHelpers(state);
    long prevScore = 0;
    boolean hasPrev = false;
    try {
      for (int depth = 1; depth <= MAX_DEPTH && nodes < limit; depth++) {
        GoResult result;
        try {
          result = atDepthAspirated(state, depth, hasPrev, prevScore);
        } catch (SearchIncomplete e) {
          break;
        }
        best = result;
        best.depth = depth;
        prevScore = result.score;
        hasPrev = true;
      }
    } finally {
      stopHelpers(helpers);
    }
    best.nodes = nodes;
    best.evaluations = evaluations;
    best.budgetExhausted = nodes >= limit;
    best.searchComplete = best.depth == MAX_DEPTH;
    return best;
  }

  /**
   * The enhanced iterative-deepening loop run to exactly {@code maxDepth} with no budget — the
   * deterministic oracle for what {@link #searchWithDeadline} must return at its reported depth
   * (GoBotChooseDeadlineConsistencyTest). Package-visible for tests/diagnostics.
   */
  GoResult searchToDepth(GoState state, int maxDepth) {
    beginSearch(state, 0, 0);
    GoResult result = null;
    long prevScore = 0;
    boolean hasPrev = false;
    for (int depth = 1; depth <= maxDepth; depth++) {
      result = atDepthAspirated(state, depth, hasPrev, prevScore);
      prevScore = result.score;
      hasPrev = true;
    }
    return result;
  }

  /** Per-call reset: budgets and counters are per move; the enhanced TT persists, aged. */
  private void beginSearch(GoState state, long deadline, long limit) {
    if (state.currentPlayer() != root) {
      throw new IllegalArgumentException(
          "searcher rooted at player " + root + " asked to move for " + state.currentPlayer());
    }
    this.deadlineMillis = deadline;
    this.nodeLimit = limit;
    this.nodes = 0;
    this.evaluations = 0;
    if (enhanced) {
      tt.bumpGeneration();
      for (Action[] slot : killers) {
        slot[0] = null;
        slot[1] = null;
      }
      int cells = state.rows() * state.cols();
      if (history == null || history[0].length != cells) {
        history = new int[2][cells];
      } else {
        for (int[] side : history) {
          for (int i = 0; i < side.length; i++) {
            side[i] >>= 1; // age: last move's refutations fade, they don't dominate forever
          }
        }
      }
    }
  }

  // --- search core ---

  // Plan item 5: when a deadline abort lands mid-root-loop but the PV child completed, the best
  // fully-searched root move of the aborted iteration (set just before the rethrow in atDepth) —
  // consumed only by searchWithDeadline, ignored by the node-budget paths. Package for tests.
  GoResult partialRoot;

  /**
   * Plan item 6: aspiration half-window. Derivation: the measured median best-vs-2nd-sibling root
   * score gap is 1299 in hand-tuned units ({@code docs/nnue-v3-gauntlet.md:46-48}), so ±1500
   * brackets a typical between-iteration move swing — most iterations complete inside the window
   * and pay a narrow search instead of a full-window one. Sweep evidence (GOBOT_ASPLMR_CHECK probe,
   * 50 parity-fixture positions, hand-tuned leaf): at depth 5 δ=750/1500/3000 give
   * +1.8%/+1.6%/+4.8% nodes (aspiration doesn't pay that shallow); at depth 6 (production-depth)
   * δ=1500 gives −8.8% nodes at a 66/120 fail/iteration count — 1500 is the measured local optimum
   * and matches the plan's derivation. v3 emits the same units; NNUEV2's WDL-scaled units differ,
   * but v2 is dead (0-24) and aspiration simply re-searches more there.
   */
  // ponytail: property knob for the sweep above; retune here if the leaf's score scale changes.
  static final long ASPIRATION_DELTA = Long.getLong("gobot.aspiration.delta", 1500);

  /**
   * One iteration of the enhanced ID loops, opened with an aspiration window around the previous
   * iteration's score ({@code hasPrev}); on a fail the failing side widens 4δ, then to ±∞ —
   * standard progressive widening, so the returned result is always exact. Mate-band scores bypass
   * aspiration (±δ around a mate score is meaningless re-search churn). Full-window everywhere the
   * feature is off: parity path, multi (maxN ignores windows), depth &lt; 3, or no previous score.
   */
  private GoResult atDepthAspirated(GoState state, int depth, boolean hasPrev, long prev) {
    if (!enhanced
        || multi
        || !aspirationEnabled()
        || !hasPrev
        || depth < 3
        || Math.abs(prev) >= GoTranspositionTable.MATE_BAND) {
      return atDepth(state, depth);
    }
    long alpha = prev - ASPIRATION_DELTA;
    long beta = prev + ASPIRATION_DELTA;
    boolean widenedLow = false;
    boolean widenedHigh = false;
    while (true) {
      GoResult result = atDepth(state, depth, alpha, beta);
      long score = result.score;
      if (score <= alpha) {
        aspirationFailLows++;
        alpha = widenedLow ? -INF_SCORE : prev - 4 * ASPIRATION_DELTA;
        widenedLow = true;
      } else if (score >= beta) {
        aspirationFailHighs++;
        beta = widenedHigh ? INF_SCORE : prev + 4 * ASPIRATION_DELTA;
        widenedHigh = true;
      } else {
        return result;
      }
    }
  }

  GoResult atDepth(GoState state, int depth) {
    return atDepth(state, depth, -INF_SCORE, INF_SCORE);
  }

  private GoResult atDepth(GoState state, int depth, long alphaOrig, long betaOrig) {
    partialRoot = null;
    long key = state.hash();
    boolean hasRoot;
    Action rootTTMove;
    if (enhanced) {
      long e = tt.probe(key);
      hasRoot = e != 0L;
      rootTTMove =
          hasRoot
              ? GoTranspositionTable.decodeAction(
                  GoTranspositionTable.actionBitsOf(e), state.cols())
              : null;
    } else {
      TableEntry rootEntry = probe(key);
      hasRoot = rootEntry != null;
      rootTTMove = hasRoot ? rootEntry.bestAction : null;
    }
    List<Child> children = orderedChildren(state, rootTTMove, hasRoot, 0);
    if (children.isEmpty()) {
      return new GoResult();
    }
    children = preservingChildren(children, root);

    GoResult best = new GoResult(children.get(0).action);
    best.score = (int) -INF_SCORE;
    long bestScore = -INF_SCORE;
    List<RootMove> roots = new ArrayList<>(children.size());
    long alpha = alphaOrig;
    long beta = betaOrig;
    try {
      for (int i = 0; i < children.size(); i++) {
        Child child = children.get(i);
        long score;
        // A scout that fails low yields a bound, not a value — flagged so consumers that rank or
        // sample over the scores can skip it (see RootMove).
        boolean exact = true;
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
          } else {
            exact = false;
          }
        }
        roots.add(new RootMove(child.action, (int) score, exact));
        if (score > bestScore) {
          best.action = child.action;
          bestScore = score;
        }
        if (!multi && score > alpha) {
          alpha = score;
        }
      }
    } catch (SearchIncomplete e) {
      if (enhanced && !roots.isEmpty() && bestScore > alphaOrig) {
        // Child 0 — the previous iteration's PV, TT-ordered first — completed, so the running
        // best is at least as well-searched as the previous answer. Root best-move updates only
        // happen on exact scores (root alpha == running best, so any improvement was re-searched
        // full-window), which keeps a fail-low bound from ever displacing the PV move. Under an
        // aspiration window (alphaOrig > -INF) an all-children-fail-low prefix is bounds only —
        // the bestScore > alphaOrig guard refuses to salvage it.
        GoResult partial = new GoResult(best.action);
        partial.score = (int) bestScore;
        partial.salvaged = true;
        partial.alternatives = topAlternatives(roots, best.action);
        partialRoot = partial;
      }
      throw e;
    }
    best.score = (int) bestScore;
    // Full-window callers (parity path included) always store EXACT, as before; an aspirated
    // iteration that failed its window stores the true bound so the re-search orders off it.
    int rootFlag = FLAG_EXACT;
    if (bestScore <= alphaOrig) {
      rootFlag = FLAG_UPPER;
    } else if (bestScore >= betaOrig) {
      rootFlag = FLAG_LOWER;
    }
    storeEntry(state, key, depth, 0, rootFlag, best.action, bestScore);
    best.alternatives = topAlternatives(roots, best.action);
    return best;
  }

  /** Store dispatch: packed array TT (enhanced, mate scores ply-rebased) or GoBot's HashMap. */
  private void storeEntry(
      GoState state, long key, int depth, int ply, int flag, Action bestAction, long best) {
    if (enhanced) {
      tt.store(
          key,
          Math.min(depth, 63),
          flag,
          GoTranspositionTable.toStoredScore(best, ply),
          bestAction == null
              ? 0
              : GoTranspositionTable.encodeAction(
                  bestAction, state.cols(), state.rows() * state.cols()));
    } else {
      store(key, TableEntry.single(depth, ply, flag, bestAction, (int) best));
    }
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
    boolean hit;
    Action ttMove;
    if (enhanced) {
      // Enhanced probe (plan item 2): depth-sufficient, ply-free — a transposition reached at a
      // different distance from the root (or persisted from a previous move's search) is usable.
      // Mate scores are stored node-relative and rebased to this node's ply on the way out.
      ttProbes++;
      long e = tt.probe(key);
      hit = e != 0L;
      ttMove =
          hit
              ? GoTranspositionTable.decodeAction(
                  GoTranspositionTable.actionBitsOf(e), state.cols())
              : null;
      if (hit) {
        ttHits++;
        if (GoTranspositionTable.depthOf(e) >= depth) {
          long val = GoTranspositionTable.fromStoredScore(GoTranspositionTable.scoreOf(e), ply);
          switch (GoTranspositionTable.flagOf(e)) {
            case FLAG_EXACT:
              return val;
            case FLAG_LOWER:
              if (val >= beta) {
                return val;
              }
              alpha = Math.max(alpha, val);
              break;
            case FLAG_UPPER:
              if (val <= alpha) {
                return val;
              }
              beta = Math.min(beta, val);
              break;
            default:
              break;
          }
          if (alpha >= beta) {
            return val;
          }
        }
      }
    } else {
      TableEntry entry = probe(key);
      hit = entry != null;
      ttMove = hit ? entry.bestAction : null;
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
    }
    long alphaOrig = alpha;
    long betaOrig = beta;
    boolean maximizing = state.currentPlayer() == root;
    long best = maximizing ? -INF_SCORE : INF_SCORE;
    Action bestAction = null;

    // Staged move generation, stage A (plan item 1): with a TT best move in hand, apply and search
    // ONLY that child before materializing the ~30 siblings — each sibling costs a full grid copy
    // plus two flood fills in eliminateStuckPlayers, and at a cut-node all of that is thrown away.
    // Move-identical to the unstaged search: the TT move's +10M ordering bonus already guarantees
    // it is child 0, generation consumes no node/eval counters, and the minimax call sequence is
    // unchanged — GoBotSearchParityTest/GoBotNodeBudgetParityTest pin this byte-exactly.
    boolean searchedTTFirst = false;
    if (ttMove instanceof MoveAction && ttMoveTargetPlausible(state, (MoveAction) ttMove)) {
      searchedTTFirst = true;
      long score = minimax(state.applyGenerated(ttMove), depth - 1, alpha, beta, ply + 1);
      best = score;
      bestAction = ttMove;
      if (maximizing) {
        if (best > alpha) {
          alpha = best;
        }
      } else {
        if (best < beta) {
          beta = best;
        }
      }
      if (alpha >= beta) {
        fastPathCuts++;
        cutCount++; // fail-high at searched-child index 0
        recordCutoff(state, bestAction, depth, ply);
        int cutFlag = FLAG_EXACT;
        if (best <= alphaOrig) {
          cutFlag = FLAG_UPPER;
        } else if (best >= betaOrig) {
          cutFlag = FLAG_LOWER;
        }
        storeEntry(state, key, depth, ply, cutFlag, bestAction, best);
        return best;
      }
    }

    List<Child> children = orderedChildren(state, ttMove, hit, ply);
    if (children.isEmpty() && !searchedTTFirst) {
      evaluations++;
      return leafEval(state);
    }
    int searched = searchedTTFirst ? 1 : 0;
    for (int i = 0; i < children.size(); i++) {
      Child child = children.get(i);
      if (searchedTTFirst && child.action.equals(ttMove)) {
        continue; // already searched full-window above
      }
      searched++;
      long score;
      if (!searchedTTFirst && i == 0) {
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
        cutCount++;
        cutIndexSum += searched - 1; // fail-high index among children actually searched
        recordCutoff(state, bestAction, depth, ply);
        break;
      }
    }
    int flag = FLAG_EXACT;
    if (best <= alphaOrig) {
      flag = FLAG_UPPER;
    } else if (best >= betaOrig) {
      flag = FLAG_LOWER;
    }
    storeEntry(state, key, depth, ply, flag, bestAction, best);
    return best;
  }

  // ponytail: maxN keeps the HashMap TT and ply-exact probe — it only runs in 3+-player games,
  // which the 1v1 strength paths never reach; enhancing it would be untested dead weight.
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
    List<Child> children = orderedChildren(state, hit ? entry.bestAction : null, hit, ply);
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
    final double table; // neural ordering score; secondary sort key below the static tiers

    Child(Action action, GoState state, int order, double table) {
      this.action = action;
      this.state = state;
      this.order = order;
      this.table = table;
    }
  }

  /**
   * Guard for the TT-move-first fast path. A full 64-bit key match means the stored best action was
   * generated for this exact state, so it is legal and enumerable by {@code searchActions} — this
   * cheap bounds/kind check only shields a genuine hash collision from crashing or corrupting the
   * subtree (no BFS; connectivity is implied by the key match). PlaceNeutralsAction TT moves skip
   * the fast path entirely: search actions enumerate only a strategic SUBSET of legal neutral
   * pairs, so legality alone would not prove the move is part of the unstaged child list.
   */
  private static boolean ttMoveTargetPlausible(GoState state, MoveAction move) {
    Pos t = move.target;
    if (t.row < 0 || t.row >= state.rows() || t.col < 0 || t.col >= state.cols()) {
      return false;
    }
    Cell cell = state.at(t.row, t.col);
    return cell.kind == CellKind.EMPTY
        || (cell.kind == CellKind.NORMAL && cell.owner != state.currentPlayer());
  }

  private List<Child> orderedChildren(GoState state, Action ttMove, boolean hasTT, int ply) {
    GoPosition pos = GoPosition.of(state);
    int actor = state.currentPlayer();
    int beforeActive = activeCount(state);
    boolean useHeuristics = enhanced && history != null && ply <= MAX_DEPTH;
    // Epic 1jh: one table call scores all 144 cells (~7µs); each MoveAction reads its cell. Only
    // meaningful for the v3 feature space (12x12, players 1/2, movesLeft 0..3); anything else
    // keeps the pre-table ordering. Null table = off = current behavior.
    double[] tableScores = null;
    PolicyOrderingTable ordering = enhanced ? orderingTable : null;
    if (ordering != null
        && actor <= 2
        && state.rows() == NNUEv3Accumulator.BOARD
        && state.cols() == NNUEv3Accumulator.BOARD
        && state.movesLeft() < PolicyOrderingTable.TEMPO_SLOTS) {
      tableScores = ordering.score(state.toBoard(), actor, state.movesLeft());
    }
    List<Child> children = new ArrayList<>();
    for (Action action : pos.searchActions()) {
      if (!running()) {
        throw new SearchIncomplete();
      }
      GoState next = pos.applySearch(action);
      int order = 0;
      double table = 0.0;
      if (hasTT && action.equals(ttMove)) {
        order += 10_000_000;
      }
      if (useHeuristics && (action.equals(killers[ply][0]) || action.equals(killers[ply][1]))) {
        order +=
            5_000_000; // plan item 3: killers right after the TT move, before every static tier
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
        if (useHeuristics && actor <= 2) {
          // History below the capture bonus (capped) — biases quiet-move order, never captures.
          order += Math.min(history[actor - 1][t.row * state.cols() + t.col], 9_000);
        }
        if (tableScores != null) {
          table = tableScores[t.row * state.cols() + t.col];
        }
      }
      if (next.currentPlayer() == actor) {
        order += 100;
      }
      children.add(new Child(action, next, order, table));
    }
    // Stable descending sort (Go's sort.SliceStable): static tiers + history first, neural table
    // score breaks ties, remaining ties keep board order. With no table every secondary key is
    // 0.0, so this reduces exactly to the pre-table sort.
    //
    // Design call (stage-1 A/B, parity-fixture positions, hand-tuned leaf, searchToDepth):
    // history-primary/table-tiebreak (this code) vs table-primary/history-tiebreak —
    //   depth 4, 50 pos: 949,641 vs 949,013 nodes; mean fail-high idx 0.0155 vs 0.0114 (a wash)
    //   depth 5, 50 pos: 1,900,774 vs 2,003,042 nodes (+5.4% for table-primary);
    //                    mean fail-high idx 0.0236 vs 0.0224
    // History-primary wins on nodes-to-depth at the deeper setting: learned in-search refutations
    // outrank the static prior once history has data, while the table still orders the
    // zero-history quiet bulk (where it replaces raw board order).
    children.sort(
        Comparator.comparingInt((Child c) -> c.order)
            .reversed()
            .thenComparing(Comparator.comparingDouble((Child c) -> c.table).reversed()));
    return children;
  }

  /** Ordering as seen by the search at {@code ply} — actions only, for tests/diagnostics. */
  List<Action> orderedActions(GoState state, Action ttMove, boolean hasTT, int ply) {
    List<Action> actions = new ArrayList<>();
    for (Child c : orderedChildren(state, ttMove, hasTT, ply)) {
      actions.add(c.action);
    }
    return actions;
  }

  /**
   * Plan item 3: on a fail-high caused by a QUIET move (no capture), remember it as a killer for
   * this ply and bump its cell's history — the same refutation usually works at sibling nodes.
   */
  private void recordCutoff(GoState state, Action action, int depth, int ply) {
    if (!enhanced || history == null || ply > MAX_DEPTH || !(action instanceof MoveAction)) {
      return;
    }
    Pos t = ((MoveAction) action).target;
    Cell target = state.at(t.row, t.col);
    int actor = state.currentPlayer();
    if (target.kind == CellKind.NORMAL && target.owner != actor) {
      return; // captures already order high statically
    }
    Action[] slot = killers[ply];
    if (!action.equals(slot[0])) {
      slot[1] = slot[0];
      slot[0] = action;
    }
    if (actor <= 2) {
      int idx = t.row * state.cols() + t.col;
      history[actor - 1][idx] = Math.min(history[actor - 1][idx] + depth * depth, 1 << 28);
    }
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

  /** v3 is 12x12-only; other sizes fall back to the hand-tuned leaf rather than throwing. */
  private static boolean v3Usable(Board board, V3Eval v3) {
    return v3 != null
        && board.rows == NNUEv3Accumulator.BOARD
        && board.cols == NNUEv3Accumulator.BOARD;
  }

  private long leafEval(GoState state) {
    if (leafMode == LeafEval.NNUEV3) {
      // Already in hand-tuned units (fitted against them) — no scale. STM-relative like v1/v2:
      // query from the leaf's own mover, then flip to root's perspective by zero-sum negation.
      Board board = state.toBoard();
      if (v3Usable(board, nnueV3)) {
        int mover = state.currentPlayer();
        long v = nnueV3Leaf(board, mover, state.movesLeft(), nnueV3);
        return mover == root ? v : -v;
      }
      // fall through to hand-tuned
    }
    if (leafMode == LeafEval.NNUEV2) {
      // v2 net is side-to-move relative (same rationale as v1 below): query from the leaf's own
      // mover, then flip to root's perspective by zero-sum negation.
      int mover = state.currentPlayer();
      long v = nnueV2Leaf(state.toBoard(), mover, nnueV2);
      return mover == root ? v : -v;
    }
    if (leafMode == LeafEval.NNUE) {
      // The net is side-to-move relative (trained on features mapped to the mover with an
      // STM-relative target). Query it from the leaf's own mover — in-distribution — then flip to
      // root's perspective by zero-sum negation. Mapping straight to root (as HandTunedEval can,
      // since it also receives currentPlayer for tempo) would evaluate opponent-to-move leaves a
      // tempo out of distribution. Valid on the 2-player bench; maxN uses leafEvalAll below.
      int mover = state.currentPlayer();
      long v = nnueLeaf(state.toBoard(), mover, nnueModel);
      return mover == root ? v : -v;
    }
    return HandTunedEval.staticEval(
        state.toBoard(), root, state.currentPlayer(), state.movesLeft(), state.neutralUsed);
  }

  private long[] leafEvalAll(GoState state) {
    Board board = state.toBoard();
    long[] all = new long[4];
    // ponytail: NNUE feature map is 2-player (opponent = 3 - player); for players 3/4 it maps only
    // own stones. The clean 2-player gauntlet/test bench never hits maxN, so this is fine.
    NNUEModel model = leafMode == LeafEval.NNUE ? nnueModel : null;
    NNUEv2Evaluator v2 = leafMode == LeafEval.NNUEV2 ? nnueV2 : null;
    V3Eval v3 = leafMode == LeafEval.NNUEV3 && v3Usable(board, nnueV3) ? nnueV3 : null;
    for (int p = 1; p <= 4; p++) {
      if (v3 != null) {
        all[p - 1] = nnueV3Leaf(board, p, state.movesLeft(), v3);
      } else if (v2 != null) {
        all[p - 1] = nnueV2Leaf(board, p, v2);
      } else if (model != null) {
        all[p - 1] = nnueLeaf(board, p, model);
      } else {
        all[p - 1] =
            HandTunedEval.staticEval(
                board, p, state.currentPlayer(), state.movesLeft(), state.neutralUsed);
      }
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
   * NNUE v2 leaf value oriented to {@code player} (higher = better for {@code player}): {@code
   * round((wdl - 0.5) * NNUEV2_SCALE)} clamped strictly inside {@code ±MATE_SCORE}.
   */
  static long nnueV2Leaf(Board board, int player, NNUEv2Evaluator v2) {
    double wdl = v2.evaluate(board, player);
    long scaled = Math.round((wdl - 0.5) * NNUEV2_SCALE);
    return Math.max(-NNUE_CLAMP, Math.min(NNUE_CLAMP, scaled));
  }

  /**
   * NNUE v3 leaf value oriented to {@code player}. The evaluator already emits hand-tuned eval
   * units, so this only rounds to the search's {@code long} frame and clamps strictly inside {@code
   * ±MATE_SCORE} — deliberately no scale knob (see {@link V3Eval}).
   */
  static long nnueV3Leaf(Board board, int player, int movesLeft, V3Eval v3) {
    long score = Math.round(v3.evaluate(board, player, movesLeft));
    return Math.max(-NNUE_CLAMP, Math.min(NNUE_CLAMP, score));
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
