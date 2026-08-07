package com.engine.nnue_trainer.train;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.engine.nnue_trainer.v2.PatternContract;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The policy emitter's contract: one row per recorded action with the state threaded through the
 * turn (real movesLeft), mover-relative symbols, and the flat-cell action labels the python trainer
 * decodes.
 */
class MctsPolicyDatasetEmitterTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void perActionRowsThreadTurnStateAndEncodeLabels() throws Exception {
    JsonNode turns =
        MAPPER.readTree(
            "[{\"player\":1,\"moves\":[{\"type\":\"place\",\"row\":0,\"col\":1},"
                + "{\"type\":\"place\",\"row\":1,\"col\":1},{\"type\":\"place\",\"row\":1,\"col\":0}]},"
                + "{\"player\":2,\"moves\":[{\"type\":\"place\",\"row\":10,\"col\":11}]},"
                + "{\"player\":1,\"moves\":[{\"type\":\"neutral\",\"cells\":"
                + "[{\"row\":0,\"col\":1},{\"row\":1,\"col\":1}]}]}]");
    assertEquals(null, GamesDbReplay.replay(12, 12, turns).skipReason, "fixture game replays");

    List<ObjectNode> rows = MctsPolicyDatasetEmitter.perActionRows("g1", turns);
    assertEquals(5, rows.size(), "3 P1 moves + 1 P2 move + 1 neutral pair");

    // Turn 1: movesLeft threads 3 -> 2 -> 1 within the turn (not per-turn snapshots).
    assertEquals(3, rows.get(0).get("ml").asInt());
    assertEquals(2, rows.get(1).get("ml").asInt());
    assertEquals(1, rows.get(2).get("ml").asInt());

    // Row 0: P1 to move — own base at cell 0, opponent base at cell 143; label = cell 0*12+1.
    assertEquals(PatternContract.BASE_SELF, rows.get(0).get("sym").get(0).asInt());
    assertEquals(PatternContract.BASE_OPPONENT, rows.get(0).get("sym").get(143).asInt());
    assertEquals("m", rows.get(0).get("t").asText());
    assertEquals(1, rows.get(0).get("a").asInt());

    // Row 3: P2 to move — the SAME cells flip to the mover-relative frame.
    ObjectNode p2 = rows.get(3);
    assertEquals(PatternContract.BASE_OPPONENT, p2.get("sym").get(0).asInt());
    assertEquals(PatternContract.BASE_SELF, p2.get("sym").get(143).asInt());
    assertEquals(10 * 12 + 11, p2.get("a").asInt());

    // Row 4: the neutral pair — canonical i<j cell label and the owned-cell list that spans the
    // pair action space; the pair is only legal at turn start, so ml is 3.
    ObjectNode pair = rows.get(4);
    assertEquals("p", pair.get("t").asText());
    assertEquals(3, pair.get("ml").asInt());
    assertEquals(1, pair.get("a").get(0).asInt());
    assertEquals(13, pair.get("a").get(1).asInt());
    assertEquals(0, pair.get("nuo").asInt(), "neutral not yet spent in the pre-action state");
    boolean sawLabelCells = false;
    for (JsonNode cell : pair.get("oc")) {
      sawLabelCells |= cell.asInt() == 1;
    }
    assertTrue(sawLabelCells, "owned-cell list must cover the labelled pair");
    assertTrue(pair.get("lm").size() > 0, "move actions stay legal alongside the pair");
  }
}
