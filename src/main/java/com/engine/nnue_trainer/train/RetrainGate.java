package com.engine.nnue_trainer.train;

import com.engine.nnue_trainer.nnue.NNUEModel;
import com.engine.nnue_trainer.train.GauntletMatch.Config;
import com.engine.nnue_trainer.train.GauntletMatch.Result;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Phase 3 Task 3: the per-generation promotion gate, one process invocation per generation. Given a
 * freshly-trained challenger and the current champion ({@code nnue_weights.json}), it plays the
 * three offline {@link GauntletMatch}es the {@link ChampionStore} rule needs — challenger-vs-champion,
 * challenger-vs-bar, champion-vs-bar — applies {@link ChampionStore#shouldPromote}, then promotes
 * (atomically overwrites the champion) or keeps, appending a history line either way.
 *
 * <p>The bash loop {@code td_retrain_loop.sh} trains the challenger and calls this once per gen; the
 * decision + weights mutation live here (in Java) so the loop never parses match output or races on
 * the weights file.
 *
 * <p>Run: {@code java -cp <cp> com.engine.nnue_trainer.train.RetrainGate <gen> <challenger.json>
 * <champion.json> <history.log>}. Env knobs: {@code GAUNTLET_GAMES}, {@code GAUNTLET_NODE_LIMIT},
 * {@code GAUNTLET_SEED}, {@code PROMOTE_MARGIN}. Prints a single {@code RUN gen=..} summary line
 * (stdout) for the run log; exit 0 = kept, 10 = promoted (so the loop can react without parsing).
 */
public final class RetrainGate {

  private RetrainGate() {}

  static Config configFromEnv() {
    Config config = new Config();
    config.games = intEnv("GAUNTLET_GAMES", config.games);
    config.nodeLimit = longEnv("GAUNTLET_NODE_LIMIT", config.nodeLimit);
    config.seed = longEnv("GAUNTLET_SEED", config.seed);
    return config;
  }

  private static int intEnv(String key, int def) {
    String v = System.getenv(key);
    return (v == null || v.isBlank()) ? def : Integer.parseInt(v.trim());
  }

  private static long longEnv(String key, long def) {
    String v = System.getenv(key);
    return (v == null || v.isBlank()) ? def : Long.parseLong(v.trim());
  }

  public static void main(String[] args) throws IOException {
    if (args.length < 4) {
      System.err.println("usage: RetrainGate <gen> <challenger.json> <champion.json> <history.log>");
      System.exit(2);
    }
    int gen = Integer.parseInt(args[0]);
    Path challengerPath = Path.of(args[1]);
    Path championPath = Path.of(args[2]);
    Path historyLog = Path.of(args[3]);

    Config config = configFromEnv();
    NNUEModel challenger = NNUEModel.load(challengerPath);
    NNUEModel champion = NNUEModel.load(championPath);

    // Three offline matches: the same fixed seed/budget keeps each generation reproducible.
    Result vsChampion = GauntletMatch.play(challenger, champion, config);
    Result challengerVsBar = GauntletMatch.play(challenger, null, config);
    Result championVsBar = GauntletMatch.play(champion, null, config);

    int margin = ChampionStore.promoteMarginFromEnv();
    boolean promote =
        ChampionStore.shouldPromote(vsChampion, challengerVsBar, championVsBar, margin);

    ChampionStore store = new ChampionStore(championPath, historyLog);
    if (promote) {
      store.promote(gen, challengerPath, vsChampion, challengerVsBar);
    } else {
      store.reject(gen, vsChampion, challengerVsBar);
    }

    System.out.printf(
        "RUN gen=%d promoted=%s vsChampion=[%s] margin=%+d challengerVsBar=%+d championVsBar=%+d%n",
        gen,
        promote,
        vsChampion,
        vsChampion.margin(),
        challengerVsBar.margin(),
        championVsBar.margin());
    System.exit(promote ? 10 : 0);
  }
}
