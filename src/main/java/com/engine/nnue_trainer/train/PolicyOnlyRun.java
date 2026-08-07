package com.engine.nnue_trainer.train;

import com.engine.nnue_trainer.board.Action;
import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.mcts.MctsSearcher;
import com.engine.nnue_trainer.mcts.PolicyNetPrior;
import com.engine.nnue_trainer.mcts.PolicyOnlySide;
import com.engine.nnue_trainer.search.gobot.GoState;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

/**
 * Standalone smoke runner for {@link PolicyOnlySide} — strength vs MCTS and raw throughput. Kept
 * out of {@code GauntletMatch} on purpose (its side dispatch is frozen by a parallel branch); the
 * game loop here mirrors its color-flipped pairs + seeded epsilon opening.
 *
 * <p>Run: {@code java -cp target/classes com.engine.nnue_trainer.train.PolicyOnlyRun [games] [sims]
 * [seed]} (defaults 20, 64, 11). Env: {@code MCTS_PRIOR} (default {@code mcts_policy.json}). Two
 * arms:
 *
 * <ul>
 *   <li>policy-only (argmax) vs MCTS at {@code sims} sims/move — W-L-D and per-side moves/s;
 *   <li>policy-only vs policy-only at τ=1 (both sampled, seeded) — the self-play condition;
 *       games/min is the number that matters.
 * </ul>
 */
public final class PolicyOnlyRun {

  private PolicyOnlyRun() {}

  interface Mover {
    Action choose(GoState state);
  }

  /** Per-side timing accumulator. */
  static final class Clock {
    long nanos;
    long moves;

    double movesPerSec() {
      return nanos == 0 ? 0 : moves * 1e9 / nanos;
    }
  }

  public static void main(String[] args) throws Exception {
    int games = args.length > 0 ? Integer.parseInt(args[0]) : 20;
    int sims = args.length > 1 ? Integer.parseInt(args[1]) : 64;
    long seed = args.length > 2 ? Long.parseLong(args[2]) : 11L;

    String priorPath = System.getProperty("MCTS_PRIOR", "mcts_policy.json");
    PolicyNetPrior net = PolicyNetPrior.load(Path.of(priorPath));

    MctsSearcher.Config mc = new MctsSearcher.Config();
    mc.prior = net;

    // Arm 1: policy-only argmax vs MCTS-sims. Both deterministic, so opening diversity comes
    // from the same seeded epsilon opening GauntletMatch uses.
    System.out.printf(
        "=== policy-only (argmax, %s) vs MCTS %d sims: %d games, seed %d ===%n",
        priorPath, sims, games, seed);
    PolicyOnlySide argmax = new PolicyOnlySide(net, 0, 0);
    runArm(
        games,
        seed,
        0.15,
        s -> argmax.choose(s),
        s -> MctsSearcher.chooseSims(s, sims, mc),
        "policy",
        "mcts");

    // Arm 2: τ=1 self-play condition — sampling is the diversity, epsilon off.
    System.out.printf("%n=== policy-only τ=1 vs policy-only τ=1: %d games ===%n", games);
    PolicyOnlySide sa = new PolicyOnlySide(net, 1.0, seed);
    PolicyOnlySide sb = new PolicyOnlySide(net, 1.0, seed + 1);
    runArm(games, seed, 0, s -> sa.choose(s), s -> sb.choose(s), "policyA", "policyB");
  }

  /** Plays color-flipped pairs of A vs B; prints W-L-D (A's view), per-side moves/s, games/min. */
  static void runArm(
      int games, long seed, double epsilon, Mover a, Mover b, String nameA, String nameB) {
    int wins = 0;
    int losses = 0;
    int draws = 0;
    Clock clockA = new Clock();
    Clock clockB = new Clock();
    long t0 = System.currentTimeMillis();
    for (int game = 0; game < games; game++) {
      boolean aIsP1 = game % 2 == 0;
      int winner =
          playGame(a, b, aIsP1, GauntletMatch.deriveGameSeed(seed, game), epsilon, clockA, clockB);
      if (winner == 0) {
        draws++;
      } else if ((winner == 1) == aIsP1) {
        wins++;
      } else {
        losses++;
      }
    }
    double secs = (System.currentTimeMillis() - t0) / 1000.0;
    System.out.printf(
        "%s %d-%d-%d (W-L-D) of %d in %.1fs%n", nameA, wins, losses, draws, games, secs);
    System.out.printf(
        "%s: %.0f moves/s (%d moves)  |  %s: %.0f moves/s (%d moves)  |  %.1f games/min%n",
        nameA,
        clockA.movesPerSec(),
        clockA.moves,
        nameB,
        clockB.movesPerSec(),
        clockB.moves,
        secs == 0 ? 0 : games * 60.0 / secs);
  }

  private static int playGame(
      Mover a,
      Mover b,
      boolean aIsP1,
      long openingSeed,
      double epsilon,
      Clock clockA,
      Clock clockB) {
    GoState state = GoState.fromBoard(freshBoard(), 1, GoState.ACTIONS_PER_TURN, new boolean[2]);
    int maxPlies = 100 * GoState.ACTIONS_PER_TURN;
    int exploreWindow = 8 * GoState.ACTIONS_PER_TURN;
    Random random = new Random(openingSeed);
    for (int ply = 0; ply < maxPlies && !state.gameOver(); ply++) {
      List<Action> legal = state.legalActions();
      if (legal.isEmpty()) {
        break;
      }
      boolean aMoves = (state.currentPlayer() == 1) == aIsP1;
      Mover mover = aMoves ? a : b;
      Clock clock = aMoves ? clockA : clockB;
      long n0 = System.nanoTime();
      Action searched = mover.choose(state);
      clock.nanos += System.nanoTime() - n0;
      clock.moves++;
      Action chosen =
          epsilon > 0 && ply < exploreWindow && random.nextDouble() < epsilon
              ? legal.get(random.nextInt(legal.size()))
              : searched != null ? searched : legal.get(0);
      state = state.applyGenerated(chosen);
    }
    return state.gameOver() ? state.winner() : 0;
  }

  private static Board freshBoard() {
    Board board = new Board(12, 12);
    board.setCell(0, 0, new Cell(1, CellKind.BASE));
    board.setCell(11, 11, new Cell(2, CellKind.BASE));
    return board;
  }
}
