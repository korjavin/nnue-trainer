package com.engine.nnue_trainer.train;

import com.engine.nnue_trainer.mcts.MctsSearcher;
import com.engine.nnue_trainer.mcts.MctsSide;
import com.engine.nnue_trainer.mcts.PolicyPrior;

/**
 * Phase 1 gauntlet (plan 20260807-mcts-az-feasibility, gate G1): the PUCT/MCTS searcher (side A) vs
 * the alpha-beta clone with the hand-tuned leaf (side B). Both sides share the same evaluation
 * knowledge — the question is purely whether PUCT search can live with alpha-beta on this game.
 *
 * <p>Run: {@code java -cp target/classes com.engine.nnue_trainer.train.GauntletMctsRun [games]
 * [moveMs] [seed] [sims]}. Defaults: 400 games, 1000 ms/move, seed 11, sims 0.
 *
 * <ul>
 *   <li>{@code sims == 0} (default): rung 1, fixed time — both sides at {@code moveMs} wall clock
 *       per move (the clone via {@code chooseWithDeadline}, opening book on, as in production).
 *   <li>{@code sims > 0}: rung 2, fixed compute — MCTS at {@code sims} sims/move, the clone at its
 *       live 60k-node budget.
 * </ul>
 *
 * <p>Env knobs (100-game screen tuning): {@code MCTS_CPUCT} (default 1.5), {@code MCTS_VALUE_SCALE}
 * (default 12000), {@code MCTS_PRIOR=<policy weights json>} for the trained prior (default
 * uniform). Gate G1: best arm ≥15% at 1 s/move over 400 games, seed ranges spaced ≥1000 between
 * runs (bead riy).
 */
public final class GauntletMctsRun {

  private GauntletMctsRun() {}

  public static void main(String[] args) throws Exception {
    int games = args.length > 0 ? Integer.parseInt(args[0]) : 400;
    long moveMs = args.length > 1 ? Long.parseLong(args[1]) : 1000L;
    long seed = args.length > 2 ? Long.parseLong(args[2]) : 11L;
    int sims = args.length > 3 ? Integer.parseInt(args[3]) : 0;

    MctsSearcher.Config mc = new MctsSearcher.Config();
    mc.cpuct = Double.parseDouble(sysval("MCTS_CPUCT", "1.5"));
    mc.valueScale = Double.parseDouble(sysval("MCTS_VALUE_SCALE", "12000"));
    String priorPath = sysval("MCTS_PRIOR", "");
    String priorName = "uniform";
    if (!priorPath.isBlank()) {
      mc.prior = com.engine.nnue_trainer.mcts.PolicyNetPrior.load(java.nio.file.Path.of(priorPath));
      priorName = priorPath;
    } else {
      mc.prior = PolicyPrior.UNIFORM;
    }

    GauntletMatch.Config cfg = new GauntletMatch.Config();
    cfg.games = games;
    cfg.seed = seed;
    if (sims > 0) {
      cfg.moveMillis = 0;
      cfg.nodeLimit = 60_000L; // the clone's live budget — fixed-compute control
      cfg.fixedDepth = 0;
    } else {
      cfg.moveMillis = moveMs;
    }

    String mode =
        sims > 0 ? ("fixed-compute sims=" + sims + " vs 60k nodes") : (moveMs + " ms/move");
    System.out.printf(
        "=== MCTS (prior=%s, cpuct=%.2f, scale=%.0f) vs hand-tuned clone: %d games, %s, seed %d ===%n",
        priorName, mc.cpuct, mc.valueScale, cfg.games, mode, seed);

    long t0 = System.currentTimeMillis();
    GauntletMatch.Result r = GauntletMatch.play(new MctsSide(mc, sims), null, cfg);
    double secs = (System.currentTimeMillis() - t0) / 1000.0;
    int n = r.wins + r.losses + r.draws;
    double winPct = 100.0 * (r.wins + 0.5 * r.draws) / n;
    System.out.printf(
        "MCTS %d-%d-%d (W-L-D) of %d, win%% %.1f (margin %+d) in %.0fs%n",
        r.wins, r.losses, r.draws, n, winPct, r.margin(), secs);
  }

  private static String sysval(String key, String fallback) {
    String v = System.getProperty(key, System.getenv(key));
    return v != null && !v.isBlank() ? v : fallback;
  }
}
