package com.engine.nnue_trainer.train;

import com.engine.nnue_trainer.nnue.NNUEModel;
import com.engine.nnue_trainer.v3.V3Eval;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * bead d4a.6.2 — the strength gauntlet for the NNUE v3 leaf eval. Runs the v3 evaluator as a GoBot
 * leaf against the hand-tuned bar (and v1 NNUE if weights are present) via {@link GauntletMatch},
 * at fixed depth so the comparison isolates eval QUALITY from the leaf's speed, and prints a
 * results table from v3's perspective.
 *
 * <p>Fixed depth matters here in a way it did not for v2: the v3 leaf benches ~5x faster than
 * hand-tuned, so an equal-time comparison would measure that speed edge rather than the eval. Depth
 * parity is the honest test of the distillation; the speed is a separate, additive win.
 *
 * <p>Expectation: v3 distills the hand-tuned STATIC eval (held-out R² ≈ 0.94–0.98, bead d4a.6.1),
 * so ~50% against the hand-tuned bar is SUCCESS — it means the learned eval reproduced the
 * hand-written one. Compare against v2's 0-24. Beating hand-tuned is not expected here and is not
 * the goal of a distillation; that is bead d4a.6.4 (re-fit on deep-search/outcome labels).
 *
 * <p>Run: {@code java -cp target/classes com.engine.nnue_trainer.train.GauntletV3Run [games]
 * [depthCsv] [seed] [nodes] [nodeLimit]} with {@code NNUEV3_WEIGHTS} optionally pointing elsewhere,
 * or {@code V3EVAL=net} (+ {@code NNUEV3NET_WEIGHTS}) to gauntlet the hidden-layer net instead.
 * Defaults: 24 games, depths 3,4, seed 7. {@code MATCHUP} filters: {@code bar} (vs hand-tuned
 * only), {@code v1} (vs v1 only), {@code both} (default).
 */
public final class GauntletV3Run {

  private static final Path V1_WEIGHTS = Path.of("src", "main", "resources", "nnue_weights.json");

  private GauntletV3Run() {}

  public static void main(String[] args) throws Exception {
    int games = args.length > 0 ? Integer.parseInt(args[0]) : 24;
    int[] depths = args.length > 1 ? parseInts(args[1]) : new int[] {3, 4};
    long seed = args.length > 2 ? Long.parseLong(args[2]) : 7L;
    boolean nodeMode = args.length > 3 && "nodes".equalsIgnoreCase(args[3]);
    long nodeLimit = args.length > 4 ? Long.parseLong(args[4]) : 60_000L;

    // V3EVAL=net gauntlets the hidden-layer net instead of the linear fit; same search, same
    // opponents, same seeds, so the two runs are directly comparable.
    long t0 = System.currentTimeMillis();
    V3Eval v3 = V3Eval.fromEnv();
    System.out.printf(
        "v3 leaf: %s loaded in %.2fs%n",
        v3.getClass().getSimpleName(), (System.currentTimeMillis() - t0) / 1000.0);

    NNUEModel v1 = null;
    if (Files.exists(V1_WEIGHTS)) {
      v1 = NNUEModel.load(V1_WEIGHTS);
      System.out.printf("v1 NNUE loaded from %s%n", V1_WEIGHTS);
    } else {
      System.out.printf(
          "v1 NNUE weights not found at %s — skipping v3-vs-v1 matchup%n", V1_WEIGHTS);
    }

    System.out.println();
    System.out.println("matchup            mode        games  W-L-D (v3)   win%(v3)   secs");
    System.out.println("-----------------  ----------  -----  -----------  --------   ------");

    String matchup = sysval("MATCHUP", "both").toLowerCase();
    boolean doBar = !matchup.equals("v1");
    boolean doV1 = !matchup.equals("bar");
    for (int depth : depths) {
      if (doBar) {
        runOne("v3 vs HAND_TUNED", v3, null, games, depth, seed, nodeMode, nodeLimit);
      }
      if (doV1 && v1 != null) {
        runOne("v3 vs v1-NNUE", v3, v1, games, depth, seed, nodeMode, nodeLimit);
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
