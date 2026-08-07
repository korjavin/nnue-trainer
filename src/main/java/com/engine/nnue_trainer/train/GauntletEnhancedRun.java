package com.engine.nnue_trainer.train;

/**
 * A/B verification of the enhanced search stack (bead 1jh.2): side A = persistent enhanced
 * searcher (packed TT + cross-move warmth, killers/history, deadline salvage), side B = the
 * pre-overhaul one-shot baseline. Both sides play the HAND_TUNED leaf, both on the same wall-clock
 * budget, so any win-rate gap is pure search improvement.
 *
 * <p>CLI: {@code GauntletEnhancedRun [games] [moveMillis] [seed]}. Defaults 50 games, 250 ms, seed
 * 1000. Prints one result line (A's perspective).
 */
public final class GauntletEnhancedRun {

  private GauntletEnhancedRun() {}

  public static void main(String[] args) {
    int games = args.length > 0 ? Integer.parseInt(args[0]) : 50;
    long ms = args.length > 1 ? Long.parseLong(args[1]) : 250L;
    long seed = args.length > 2 ? Long.parseLong(args[2]) : 1000L;

    GauntletMatch.Config cfg = new GauntletMatch.Config();
    cfg.games = games;
    cfg.seed = seed;
    cfg.fixedDepth = 0;
    cfg.nodeLimit = 0;
    cfg.moveMillis = ms;
    cfg.enhancedB = false;

    long t = System.currentTimeMillis();
    GauntletMatch.Result r = GauntletMatch.play(null, null, cfg);
    double secs = (System.currentTimeMillis() - t) / 1000.0;
    int n = r.wins + r.losses + r.draws;
    System.out.printf(
        "ENHANCED vs BASELINE  time=%dms  %d games  %d-%d-%d  %.1f%%  %.1fs%n",
        ms, n, r.wins, r.losses, r.draws, 100.0 * (r.wins + 0.5 * r.draws) / n, secs);
  }
}
