package com.engine.nnue_trainer.train;

import com.engine.nnue_trainer.nnue.NNUEModel;
import com.engine.nnue_trainer.v2.NNUEv2Evaluator;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * bead d4a.1.4 — the strength gauntlet for the NNUE v2 leaf eval. Runs the v2 evaluator as a GoBot
 * leaf against the hand-tuned bar (and v1 NNUE if weights are present) via {@link GauntletMatch},
 * at fixed depth (isolates eval quality from the v2 prototype's slowness), and prints a results
 * table from v2's perspective.
 *
 * <p>Run: {@code java -cp target/classes com.engine.nnue_trainer.train.GauntletV2Run [games]
 * [depthCsv] [seed]} with {@code NNUEV2_WEIGHTS}/{@code NNUEV2_DICT} pointing at the v2 blob+dict.
 * Defaults: 24 games, depths 3,4, seed 7.
 */
public final class GauntletV2Run {

  private static final Path V1_WEIGHTS = Path.of("src", "main", "resources", "nnue_weights.json");

  private GauntletV2Run() {}

  public static void main(String[] args) throws Exception {
    int games = args.length > 0 ? Integer.parseInt(args[0]) : 24;
    int[] depths = args.length > 1 ? parseInts(args[1]) : new int[] {3, 4};
    long seed = args.length > 2 ? Long.parseLong(args[2]) : 7L;
    boolean nodeMode = args.length > 3 && "nodes".equalsIgnoreCase(args[3]);
    long nodeLimit = args.length > 4 ? Long.parseLong(args[4]) : 60_000L;

    Path w = Path.of(sysval("NNUEV2_WEIGHTS", NNUEv2Evaluator.DEFAULT_WEIGHTS.toString()));
    Path d = Path.of(sysval("NNUEV2_DICT", NNUEv2Evaluator.DEFAULT_DICT.toString()));
    System.out.printf("Loading v2 evaluator: weights=%s dict=%s%n", w, d);
    long t0 = System.currentTimeMillis();
    NNUEv2Evaluator v2 = NNUEv2Evaluator.load(w, d);
    System.out.printf("v2 loaded in %.1fs%n", (System.currentTimeMillis() - t0) / 1000.0);

    NNUEModel v1 = null;
    if (Files.exists(V1_WEIGHTS)) {
      v1 = NNUEModel.load(V1_WEIGHTS);
      System.out.printf("v1 NNUE loaded from %s%n", V1_WEIGHTS);
    } else {
      System.out.printf(
          "v1 NNUE weights not found at %s — skipping v2-vs-v1 matchup%n", V1_WEIGHTS);
    }

    System.out.println();
    System.out.println("matchup            mode        games  W-L-D (v2)   win%(v2)   secs");
    System.out.println("-----------------  ----------  -----  -----------  --------   ------");

    // MATCHUP env filters which matchups run: "bar" (vs hand-tuned only), "v1" (vs v1 only), or
    // "both" (default). Lets the slow v2-vs-v1 matchup be sized separately from the cheap bar.
    String matchup = sysval("MATCHUP", "both").toLowerCase();
    boolean doBar = !matchup.equals("v1");
    boolean doV1 = !matchup.equals("bar");
    for (int depth : depths) {
      if (doBar) {
        runOne("v2 vs HAND_TUNED", v2, null, games, depth, seed, nodeMode, nodeLimit);
      }
      if (doV1 && v1 != null) {
        runOne("v2 vs v1-NNUE", v2, v1, games, depth, seed, nodeMode, nodeLimit);
      }
    }
    System.out.println("\nDONE");
  }

  private static void runOne(
      String label,
      Object a,
      Object b,
      int games,
      int depth,
      long seed,
      boolean nodeMode,
      long nodeLimit) {
    GauntletMatch.Config cfg = new GauntletMatch.Config();
    cfg.games = games;
    cfg.seed = seed;
    if (nodeMode) {
      cfg.fixedDepth = 0;
      cfg.nodeLimit = nodeLimit;
    } else {
      cfg.fixedDepth = depth;
      cfg.nodeLimit = 0;
    }
    String mode = nodeMode ? ("nodes=" + nodeLimit) : ("depth=" + depth);
    long t = System.currentTimeMillis();
    GauntletMatch.Result r = GauntletMatch.play(a, b, cfg);
    double secs = (System.currentTimeMillis() - t) / 1000.0;
    int n = r.wins + r.losses + r.draws;
    double winPct = 100.0 * (r.wins + 0.5 * r.draws) / n;
    System.out.printf(
        "%-17s  %-10s  %5d  %3d-%d-%-5d  %6.1f%%   %6.1f%n",
        label, mode, n, r.wins, r.losses, r.draws, winPct, secs);
    System.out.flush();
  }

  private static int[] parseInts(String csv) {
    String[] parts = csv.split(",");
    int[] out = new int[parts.length];
    for (int i = 0; i < parts.length; i++) {
      out[i] = Integer.parseInt(parts[i].trim());
    }
    return out;
  }

  private static String sysval(String key, String fallback) {
    String v = System.getProperty(key, System.getenv(key));
    return v != null ? v : fallback;
  }
}
