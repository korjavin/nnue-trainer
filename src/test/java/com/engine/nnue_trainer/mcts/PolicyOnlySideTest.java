package com.engine.nnue_trainer.mcts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.engine.nnue_trainer.board.Action;
import com.engine.nnue_trainer.search.gobot.GoState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * PolicyOnlySide contract: never an illegal action, deterministic under a fixed seed, temperature
 * sampling matches the net's masked distribution — plus the policy-only self-play generator's row
 * schema (same shape as {@code SelfPlayMcts}, pv = the masked softmax).
 */
class PolicyOnlySideTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final int FLAT = 144 + 144 * 144;

  private static PolicyNetPrior net;

  @BeforeAll
  static void loadNet() throws IOException {
    net = PolicyNetPrior.load(Path.of("mcts_policy.json"));
  }

  /** 200 random positions (random-walk games): the chosen action always validates via apply(). */
  @Test
  void neverPlaysIllegal() {
    Random rng = new Random(7);
    PolicyOnlySide greedy = new PolicyOnlySide(net, 0, 1);
    PolicyOnlySide sampled = new PolicyOnlySide(net, 1.0, 2);
    int checked = 0;
    while (checked < 200) {
      GoState state = SelfPlayMcts.freshState();
      for (int ply = 0; ply < 60 && !state.gameOver() && checked < 200; ply++) {
        List<Action> legal = state.legalActions();
        if (legal.isEmpty()) {
          break;
        }
        PolicyOnlySide side = checked % 2 == 0 ? greedy : sampled;
        Action chosen = side.choose(state);
        assertNotNull(chosen);
        assertNotNull(state.apply(chosen), "illegal action at ply " + ply + ": " + chosen);
        checked++;
        state = state.applyGenerated(legal.get(rng.nextInt(legal.size())));
      }
    }
    assertEquals(200, checked);
  }

  /** Same seed → the same full game, choice for choice (τ=1, the sampling path). */
  @Test
  void deterministicWithFixedSeed() {
    List<Action> first = playout(new PolicyOnlySide(net, 1.0, 42));
    List<Action> second = playout(new PolicyOnlySide(net, 1.0, 42));
    assertEquals(first, second);
    assertTrue(first.size() > 10);
  }

  private static List<Action> playout(PolicyOnlySide side) {
    GoState state = SelfPlayMcts.freshState();
    List<Action> actions = new ArrayList<>();
    for (int ply = 0; ply < 120 && !state.gameOver(); ply++) {
      Action a = side.choose(state);
      if (a == null) {
        break;
      }
      actions.add(a);
      state = state.applyGenerated(a);
    }
    return actions;
  }

  /** τ=1 empirical frequencies track the net's masked softmax; argmax picks its mode. */
  @Test
  void temperatureSamplingMatchesDistribution() {
    GoState state = SelfPlayMcts.freshState();
    List<Action> legal = state.legalActions();
    float[] p = net.priors(state, legal);

    PolicyOnlySide side = new PolicyOnlySide(net, 1.0, 5);
    int n = 4000;
    int[] counts = new int[legal.size()];
    for (int i = 0; i < n; i++) {
      counts[legal.indexOf(side.choose(state))]++;
    }
    for (int a = 0; a < p.length; a++) {
      assertEquals(p[a], counts[a] / (double) n, 0.04, "action " + a);
    }
    // Argmax side always plays the mode.
    PolicyOnlySide greedy = new PolicyOnlySide(net, 0, 9);
    assertEquals(legal.get(PolicyOnlySide.argmax(p)), greedy.choose(state));
  }

  /**
   * Generator rows: SelfPlayMcts schema, pv = masked distribution summing to 1, absolute-frame z.
   */
  @Test
  void policyOnlyGeneratorSchema() throws Exception {
    StringWriter sw = new StringWriter();
    try (BufferedWriter w = new BufferedWriter(sw)) {
      SelfPlayPolicyOnly.playGame("g", 123L, net, w, SelfPlayMcts.freshState());
    }
    String[] lines = sw.toString().split("\n");
    assertTrue(lines.length > 10);
    for (String line : lines) {
      JsonNode row = MAPPER.readTree(line);
      assertEquals("g", row.get("g").asText());
      assertEquals(144, row.get("sym").size());
      int ml = row.get("ml").asInt();
      assertTrue(ml >= 1 && ml <= GoState.ACTIONS_PER_TURN);
      int mover = row.get("mover").asInt();
      assertTrue(mover == 1 || mover == 2);
      JsonNode pi = row.get("pi");
      JsonNode pv = row.get("pv");
      assertEquals(pi.size(), pv.size());
      assertTrue(pi.size() > 1);
      double sum = 0;
      for (int a = 0; a < pi.size(); a++) {
        int flat = pi.get(a).asInt();
        assertTrue(flat >= 0 && flat < FLAT);
        double prob = pv.get(a).asDouble();
        assertTrue(prob >= 0 && prob <= 1);
        sum += prob;
      }
      assertEquals(1.0, sum, 1e-4);
      int z = row.get("z").asInt();
      assertTrue(z >= -1 && z <= 1);
    }
    // Byte-identical rerun: the (seed, game) determinism contract.
    StringWriter sw2 = new StringWriter();
    try (BufferedWriter w = new BufferedWriter(sw2)) {
      SelfPlayPolicyOnly.playGame("g", 123L, net, w, SelfPlayMcts.freshState());
    }
    assertEquals(sw.toString(), sw2.toString());
  }
}
