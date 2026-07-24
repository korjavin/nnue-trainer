package com.engine.nnue_trainer.train;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.engine.nnue_trainer.train.GauntletMatch.Config;
import org.junit.jupiter.api.Test;

/** Task 3: the gate's env-config must default to the same match settings the harness uses. */
class RetrainGateTest {

  @Test
  void configFromEnvDefaultsMatchGauntletDefaults() {
    // No GAUNTLET_* env set in the test JVM → defaults must equal a fresh GauntletMatch.Config.
    Config def = new Config();
    Config fromEnv = RetrainGate.configFromEnv();
    assertEquals(def.games, fromEnv.games);
    assertEquals(def.nodeLimit, fromEnv.nodeLimit);
  }
}
