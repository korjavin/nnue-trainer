package com.engine.nnue_trainer.train;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.engine.nnue_trainer.nnue.NNUEModel;
import com.engine.nnue_trainer.search.gobot.GoBotSearcher;
import com.engine.nnue_trainer.train.GauntletMatch.Config;
import com.engine.nnue_trainer.train.GauntletMatch.Result;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Task 4: one-generation integration smoke of the gate wiring (GauntletMatch → shouldPromote →
 * ChampionStore), tiny + offline so it runs in CI. Asserts the champion file changes ONLY on a real
 * improvement and that a per-generation history line is written either way. Mirrors what {@link
 * RetrainGate} does per generation, minus the heavy self-play/training.
 */
class RetrainSmokeTest {

  @AfterEach
  void resetDefault() {
    GoBotSearcher.configureDefaultLeafEval(GoBotSearcher.LeafEval.HAND_TUNED, null);
  }

  private static Config fastConfig() {
    Config c = new Config();
    c.fixedDepth = 2; // cheap + skips the opening book
    c.games = 2;
    c.maxTurns = 6;
    c.seed = 3;
    return c;
  }

  @Test
  void oneGenerationChampionOnlyChangesOnRealImprovement(@TempDir Path dir) throws IOException {
    Path champion = dir.resolve("nnue_weights.json");
    Path challenger = dir.resolve("challenger.json");
    Path history = dir.resolve("champions/history.log");
    Files.writeString(champion, "CHAMPION_V0");
    Files.writeString(challenger, "CHALLENGER_V1");

    NNUEModel net = NNUEModel.createDefault();
    ChampionStore store = new ChampionStore(champion, history);
    Config config = fastConfig();

    // Gen 1: challenger IS the champion (identical nets) → margin 0, no real improvement.
    Result vsChampion = GauntletMatch.play(net, net, config);
    Result challengerVsBar = GauntletMatch.play(net, null, config);
    Result championVsBar = GauntletMatch.play(net, null, config);
    assertEquals(0, vsChampion.margin(), "identical nets carry no promotion signal");
    boolean promote =
        ChampionStore.shouldPromote(
            vsChampion, challengerVsBar, championVsBar, ChampionStore.DEFAULT_PROMOTE_MARGIN);
    assertTrue(!promote, "no improvement must not promote");
    store.reject(1, vsChampion, challengerVsBar);

    assertEquals(
        "CHAMPION_V0", Files.readString(champion), "champion untouched without improvement");

    // Gen 2: a genuine improvement (challenger clears the champion by the margin) → promote.
    store.promote(2, challenger, new Result(3, 0, 0), new Result(0, 4, 0));
    assertEquals(
        "CHALLENGER_V1", Files.readString(champion), "champion replaced on real improvement");

    List<String> log = Files.readAllLines(history);
    assertEquals(2, log.size(), "one history line per generation: " + log);
    assertTrue(log.get(0).contains("gen=1") && log.get(0).contains("promoted=false"), log.get(0));
    assertTrue(log.get(1).contains("gen=2") && log.get(1).contains("promoted=true"), log.get(1));
  }
}
