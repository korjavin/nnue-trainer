package com.engine.nnue_trainer.mcts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Pins the Java value-head inference against outputs computed by {@code train_selfplay.py} from the
 * same weights — the {@code PolicyNetPriorParityTest} discipline for Phase 2's value net. The
 * committed artifact/fixture pair is a tiny smoke-trained net (8ch x 2 layers); parity is about the
 * forward pass, not strength.
 */
class ValueHeadParityTest {

  private static final Path WEIGHTS =
      Path.of("src", "test", "resources", "mcts", "mcts_selfplay_tiny.json");
  private static final Path FIXTURE =
      Path.of("src", "test", "resources", "mcts", "mcts_value_parity.json");
  private static final double TOL = 1e-3;

  @Test
  void valueMatchesPythonFixture() throws Exception {
    assertTrue(Files.exists(WEIGHTS), "tiny selfplay artifact missing: " + WEIGHTS);
    assertTrue(Files.exists(FIXTURE), "value parity fixture missing: " + FIXTURE);
    PolicyNetPrior net = PolicyNetPrior.load(WEIGHTS);
    assertTrue(net.hasValueHead(), "selfplay artifact carries a value head");

    JsonNode fixture = new ObjectMapper().readTree(Files.readAllBytes(FIXTURE));
    JsonNode samples = fixture.get("samples");
    assertTrue(samples.isArray() && samples.size() > 0, "fixture has samples");
    for (JsonNode sample : samples) {
      int[] sym = new int[144];
      for (int i = 0; i < 144; i++) {
        sym[i] = sample.get("sym").get(i).asInt();
      }
      PolicyNetPrior.Heads heads =
          net.forward(
              sym, sample.get("ml").asInt(), sample.get("nuo").asInt(), sample.get("nux").asInt());
      double expected = sample.get("value").doubleValue();
      assertTrue(Math.abs(expected) <= 1.0, "fixture value is a tanh output");
      assertEquals(expected, heads.value, TOL, "value head");
      // The policy heads must survive the value-head addition unchanged.
      for (int i = 0; i < 144; i++) {
        assertEquals(
            sample.get("move_logits").get(i).doubleValue(), heads.move[i], TOL, "move logit " + i);
      }
    }
  }

  @Test
  void policyOnlyArtifactsStillLoadWithoutAValueHead() throws Exception {
    PolicyNetPrior phase1 = PolicyNetPrior.load(Path.of("mcts_policy.json"));
    assertFalse(phase1.hasValueHead(), "Phase 1 artifact has no value head");
  }

  /** Integration: the searcher runs with the net value source and picks legally. */
  @Test
  void mctsRunsOnNetValue() throws Exception {
    PolicyNetPrior net = PolicyNetPrior.load(WEIGHTS);
    MctsSearcher.Config cfg = new MctsSearcher.Config();
    cfg.prior = net;
    cfg.valueNet = net;
    var state = SelfPlayMcts.freshState();
    MctsSearcher s = new MctsSearcher(state, cfg);
    s.runSims(60);
    assertTrue(state.legalActions().contains(s.bestAction()), "net-value search picks legally");
    assertTrue(Math.abs(s.rootValueAbs()) <= 1.0, "root value stays in the tanh range");
  }
}
