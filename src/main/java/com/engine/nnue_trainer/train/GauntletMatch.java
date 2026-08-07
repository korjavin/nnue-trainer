package com.engine.nnue_trainer.train;

import com.engine.nnue_trainer.board.Action;
import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.mcts.MctsSearcher;
import com.engine.nnue_trainer.mcts.MctsSide;
import com.engine.nnue_trainer.nnue.NNUEModel;
import com.engine.nnue_trainer.search.gobot.GoBotSearcher;
import com.engine.nnue_trainer.search.gobot.GoResult;
import com.engine.nnue_trainer.search.gobot.GoState;
import com.engine.nnue_trainer.v2.NNUEv2Evaluator;
import com.engine.nnue_trainer.v3.V3Eval;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Phase 3 Task 1: the offline net-vs-net gate. Same strong GoBot search on <b>both</b> sides,
 * differing only in the leaf evaluation — side A's weights vs side B's weights (either side may
 * instead be the {@link com.engine.nnue_trainer.search.eval.HandTunedEval} bar, {@code model ==
 * null}). Plays N games alternating colors under a fixed node budget (or fixed depth). The GoBot
 * search is fully deterministic, so each game is diversified by a seeded epsilon-greedy opening
 * (see {@link Config#seed}); the whole match is reproducible given the seed and returns A's {@code
 * {wins, losses, draws}}.
 *
 * <p>This is the per-generation promotion gate (Task 2 consumes it): fast and reproducible, unlike
 * the live vs-GoBot harness (~13.5s/move). Mirrors {@link SelfPlayGenerator}'s GoBot game loop but
 * swaps the process-wide leaf config to the current mover's side before each search.
 *
 * <p>Run: {@code ./mvnw -q compile && java -cp target/classes
 * com.engine.nnue_trainer.train.GauntletMatch challenger.json champion.json [games] [nodeLimit]}
 * (use {@code -} for a path to pin that side to the hand-tuned bar).
 */
public final class GauntletMatch {

  /** Match outcome from side A's (the challenger's) perspective. */
  public static final class Result {
    public final int wins;
    public final int losses;
    public final int draws;

    public Result(int wins, int losses, int draws) {
      this.wins = wins;
      this.losses = losses;
      this.draws = draws;
    }

    /** Score margin (A wins − A losses) — the promotion signal in Task 2. */
    public int margin() {
      return wins - losses;
    }

    @Override
    public String toString() {
      return String.format("A %d - %d B, %d draws", wins, losses, draws);
    }
  }

  public static final class Config {
    public int games = 8;
    public int maxTurns = 100;

    /** Deterministic node budget per move (0 disables — then {@code fixedDepth} must be > 0). */
    public long nodeLimit = 60_000L;

    /** Fixed search depth; &gt;0 uses {@code chooseDepth} instead of the node budget. */
    public int fixedDepth = 0;

    /**
     * Fixed wall-clock per move in millis; &gt;0 overrides depth/node budgets and plays the
     * production condition (plan 20260807 rung 1): the GoBot side uses {@code chooseWithDeadline}
     * (opening book on, as live), an MCTS side without a sim budget uses the same deadline.
     */
    public long moveMillis = 0;

    /**
     * Per-side search-enhancement switch (A/B verification of the enhanced search stack). True =
     * persistent enhanced searcher (packed TT, killers/history, salvage); false = pre-overhaul
     * one-shot baseline. Both default true — production behavior.
     */
    public boolean enhancedA = true;

    public boolean enhancedB = true;

    // Per-game opening diversity. The search is fully deterministic, so without this every game
    // from the same start position is byte-identical and N games collapse to just 2 distinct games
    // (one per color) — GAUNTLET_GAMES and PROMOTE_MARGIN granularity would then be a fiction. A
    // seeded epsilon-greedy opening (mirrors SelfPlayGenerator) branches each game pair into a
    // distinct-but-reproducible line. Per-pair seeds are derived by hashing (seed, pair index)
    // through a 64-bit mixer, so identical weights still cancel exactly (the two colors replay the
    // same opening), a real challenger produces `games` genuinely different games, and runs with
    // different seeds — even nearby ones like 7 and 8 — play disjoint opening sets, so pooled
    // results are independent samples. Seeds need no spacing.
    public long seed = 1L;
    public double epsilon = 0.15;
    public int exploreTurns = 8;

    /**
     * When set, every finished game is appended to this sqlite db in the games.db schema (see
     * {@link GameRecorder}), so gauntlet games feed the training corpus instead of being discarded.
     * Null (the default) records nothing.
     */
    public String recordDb = null;
  }

  private GauntletMatch() {}

  /**
   * Play the match. {@code modelA}/{@code modelB} are the two sides' NNUE nets; a {@code null} side
   * plays the hand-tuned bar instead. Alternates which side moves first each game.
   */
  public static Result play(NNUEModel modelA, NNUEModel modelB, Config config) {
    return play((Object) modelA, (Object) modelB, config);
  }

  /**
   * Generalized match. Each side is one of: {@code null} (hand-tuned bar), an {@link NNUEModel} (v1
   * leaf), an {@link NNUEv2Evaluator} (v2 leaf), a {@link V3Eval} (v3 leaf, linear or net) — all
   * played by the same GoBot search, only the leaf eval differing — or an {@link MctsSide}, which
   * replaces the search itself with the Phase 1 PUCT searcher.
   */
  public static Result play(Object sideA, Object sideB, Config config) {
    // Color balance (and exact cancellation of identical nets) requires games to come in
    // color-flipped pairs; an odd count leaves one unpaired game that always makes A the first
    // mover, a deterministic margin bias that never washes out. Round up to the next even count.
    config.games = (config.games + 1) & ~1;
    GoBotSearcher.LeafConfig prev =
        GoBotSearcher.configureDefaultLeafEval(GoBotSearcher.LeafEval.HAND_TUNED, (NNUEModel) null);
    try {
      return playGames(sideA, sideB, config);
    } finally {
      GoBotSearcher.restoreDefaultLeafEval(prev);
    }
  }

  private static Result playGames(Object modelA, Object modelB, Config config) {
    try (GameRecorder recorder =
        config.recordDb != null ? new GameRecorder(config.recordDb) : null) {
      int wins = 0;
      int losses = 0;
      int draws = 0;
      String nameA = sideName(modelA, config);
      String nameB = sideName(modelB, config);
      for (int game = 0; game < config.games; game++) {
        boolean aIsP1 = (game % 2 == 0);
        // Same opening seed for the two colors of a pair → identical weights cancel exactly, while
        // distinct pairs (game/2) explore distinct openings.
        long gameSeed = deriveGameSeed(config.seed, game);
        int winner = playGame(modelA, modelB, aIsP1, gameSeed, config, recorder, nameA, nameB);
        if (winner == 0) {
          draws++;
        } else if ((winner == 1) == aIsP1) {
          wins++;
        } else {
          losses++;
        }
      }
      return new Result(wins, losses, draws);
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException("gauntlet recording failed: " + config.recordDb, e);
    }
  }

  /**
   * Compact, deterministic provenance name for a recorded game's player column: {@code
   * gauntlet:<eval>:<budget>}, e.g. {@code gauntlet:v3net:250ms} or {@code gauntlet:ht:d3}.
   */
  static String sideName(Object side, Config config) {
    String eval;
    if (side == null) {
      eval = "ht";
    } else if (side instanceof MctsSide) {
      MctsSide m = (MctsSide) side;
      eval = m.sims > 0 ? "mcts:s" + m.sims : "mcts";
    } else if (side instanceof NNUEv2Evaluator) {
      eval = "nnue2";
    } else if (side instanceof V3Eval) {
      eval = side.getClass().getSimpleName().contains("Net") ? "v3net" : "v3lin";
    } else {
      eval = "nnue1";
    }
    String budget =
        config.moveMillis > 0
            ? config.moveMillis + "ms"
            : config.fixedDepth > 0 ? "d" + config.fixedDepth : "n" + config.nodeLimit;
    return "gauntlet:" + eval + ":" + budget;
  }

  /**
   * Opening seed for game index {@code game}: the two color-flipped games of a pair (2k and 2k+1)
   * share a seed so identical nets cancel exactly, and (seed, pair) is hashed through a 64-bit
   * mixer so different run seeds — additive-close ones included — yield statistically independent
   * opening sets. Package-visible for tests.
   */
  static long deriveGameSeed(long seed, int game) {
    return mix64(seed ^ (0x9E3779B97F4A7C15L * ((game / 2) + 1)));
  }

  /** SplitMix64 finalizer — a bijective 64-bit mixer with full avalanche. */
  private static long mix64(long z) {
    z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
    z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
    return z ^ (z >>> 31);
  }

  private static int playGame(
      Object modelA,
      Object modelB,
      boolean aIsP1,
      long seed,
      Config config,
      GameRecorder recorder,
      String nameA,
      String nameB)
      throws java.sql.SQLException {
    GoState state = GoState.fromBoard(freshBoard(), 1, GoState.ACTIONS_PER_TURN, new boolean[2]);
    int maxPlies = config.maxTurns * GoState.ACTIONS_PER_TURN;
    int exploreWindow = config.exploreTurns * GoState.ACTIONS_PER_TURN;
    Random random = new Random(seed);
    List<GameRecorder.Turn> turns = recorder != null ? new ArrayList<>() : null;
    String startedAt = recorder != null ? GameRecorder.now() : null;

    // One persistent enhanced searcher per player (plan item 2): the TT survives across that
    // side's moves within the game, so each search starts warm from its previous move's principal
    // subtree. Created lazily AFTER applyLeaf so each snapshots its own side's leaf eval.
    GoBotSearcher[] searcherByPlayer = new GoBotSearcher[2];

    for (int ply = 0; ply < maxPlies && !state.gameOver(); ply++) {
      List<Action> legal = state.legalActions();
      if (legal.isEmpty()) {
        break; // stuck player — GoState elimination normally sets gameOver first
      }
      Object moverModel = ((state.currentPlayer() == 1) == aIsP1) ? modelA : modelB;
      boolean moverIsA = ((state.currentPlayer() == 1) == aIsP1);
      Action searched;
      if (moverModel instanceof MctsSide) {
        MctsSide mcts = (MctsSide) moverModel;
        searched =
            mcts.sims > 0
                ? MctsSearcher.chooseSims(state, mcts.sims, mcts.config)
                : MctsSearcher.chooseDeadline(
                    state, System.currentTimeMillis() + moveBudgetMillis(config), mcts.config);
      } else {
        // Point the process-wide leaf eval at whichever side is on the move, then search.
        applyLeaf(moverModel);
        GoResult r =
            chooseMove(
                state, config, searcherByPlayer, moverIsA ? config.enhancedA : config.enhancedB);
        searched = r != null ? r.action : null;
      }

      Action chosen;
      if (ply < exploreWindow && random.nextDouble() < config.epsilon) {
        chosen = legal.get(random.nextInt(legal.size())); // seeded opening diversity
      } else {
        chosen = searched != null ? searched : legal.get(0);
      }
      GoState next = state.apply(chosen);
      if (next == null) {
        chosen = legal.get(0);
        next = state.apply(chosen);
      }
      if (turns != null) {
        // Group consecutive actions by mover — in 1v1 that IS the replayer's turn grouping (a turn
        // ends exactly when currentPlayer flips, and a player never takes two turns in a row: with
        // the opponent inactive GoState sets gameOver).
        int mover = state.currentPlayer();
        if (turns.isEmpty() || turns.get(turns.size() - 1).player != mover) {
          turns.add(new GameRecorder.Turn(mover));
        }
        turns.get(turns.size() - 1).moves.add(chosen);
      }
      state = next;
    }
    if (recorder != null) {
      // A decided game stores GoState's winner under "no_moves". A maxTurns-capped game stays a
      // DRAW for the match result below (unchanged), but is stored with outcomeWinner() — the
      // territory-tiebreak label every self-play loop uses — under "turn_cap", so a corpus consumer
      // that reads `result` gets the honest outcome label, not a fake draw.
      boolean over = state.gameOver();
      recorder.record(
          aIsP1 ? nameA : nameB,
          aIsP1 ? nameB : nameA,
          over ? state.winner() : state.outcomeWinner(),
          over ? "no_moves" : "turn_cap",
          startedAt,
          turns);
    }
    return state.gameOver() ? state.winner() : 0;
  }

  private static void applyLeaf(Object side) {
    if (side == null) {
      GoBotSearcher.configureDefaultLeafEval(GoBotSearcher.LeafEval.HAND_TUNED, (NNUEModel) null);
    } else if (side instanceof NNUEv2Evaluator) {
      GoBotSearcher.configureDefaultLeafEvalV2(
          GoBotSearcher.LeafEval.NNUEV2, (NNUEv2Evaluator) side);
    } else if (side instanceof V3Eval) {
      // Both the linear NNUEv3Evaluator and the hidden-layer NNUEv3NetEvaluator: same leaf, same
      // hand-tuned units.
      GoBotSearcher.configureDefaultLeafEvalV3(GoBotSearcher.LeafEval.NNUEV3, (V3Eval) side);
    } else {
      GoBotSearcher.configureDefaultLeafEval(GoBotSearcher.LeafEval.NNUE, (NNUEModel) side);
    }
  }

  private static GoResult chooseMove(
      GoState state, Config config, GoBotSearcher[] byPlayer, boolean enhanced) {
    if (config.fixedDepth > 0) {
      return GoBotSearcher.chooseDepth(state, config.fixedDepth); // parity oracle, stateless
    }
    if (!enhanced) {
      // Baseline arm for enhanced-vs-baseline verification: pre-overhaul behavior — one-shot
      // searcher per move, HashMap TT, ply-exact probing, no killers/history.
      GoBotSearcher one = GoBotSearcher.newSearcher(state);
      return config.moveMillis > 0
          ? one.searchWithDeadline(state, System.currentTimeMillis() + config.moveMillis)
          : one.searchNodeBudget(state, config.nodeLimit);
    }
    int mover = state.currentPlayer();
    GoBotSearcher s = byPlayer[mover - 1];
    if (s == null) {
      s = GoBotSearcher.newEnhancedSearcher(state);
      byPlayer[mover - 1] = s;
    }
    return config.moveMillis > 0
        ? s.searchWithDeadline(state, System.currentTimeMillis() + config.moveMillis)
        : s.searchNodeBudget(state, config.nodeLimit);
  }

  /** Per-move wall clock for a deadline-driven side (production default when unset). */
  private static long moveBudgetMillis(Config config) {
    return config.moveMillis > 0 ? config.moveMillis : 1000L;
  }

  private static Board freshBoard() {
    Board board = new Board(12, 12);
    board.setCell(0, 0, new Cell(1, CellKind.BASE));
    board.setCell(11, 11, new Cell(2, CellKind.BASE));
    return board;
  }

  /** A path of {@code -} pins that side to the hand-tuned bar; otherwise load the net. */
  private static NNUEModel loadSide(String path) throws IOException {
    return "-".equals(path) ? null : NNUEModel.load(Path.of(path));
  }

  public static void main(String[] args) throws IOException {
    if (args.length < 2) {
      System.err.println(
          "usage: GauntletMatch <challengerWeights|-> <championWeights|-> [games] [nodeLimit]");
      System.exit(2);
    }
    Config config = new Config();
    if (args.length > 2) {
      config.games = Integer.parseInt(args[2]);
    }
    if (args.length > 3) {
      config.nodeLimit = Long.parseLong(args[3]);
    }
    NNUEModel modelA = loadSide(args[0]);
    NNUEModel modelB = loadSide(args[1]);
    Result result = play(modelA, modelB, config);
    System.out.printf(
        "=== GauntletMatch (%s vs %s, %d games, nodeLimit=%d): %s (margin %+d) ===%n",
        args[0], args[1], config.games, config.nodeLimit, result, result.margin());
  }
}
