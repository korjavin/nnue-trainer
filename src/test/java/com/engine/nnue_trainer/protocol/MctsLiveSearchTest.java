package com.engine.nnue_trainer.protocol;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.engine.nnue_trainer.board.Action;
import com.engine.nnue_trainer.board.MoveAction;
import com.engine.nnue_trainer.board.PlaceNeutralsAction;
import com.engine.nnue_trainer.search.gobot.GoState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * SEARCH=MCTS live-path guarantees (bead 1jh.6), mirroring the {@link GameLoopHandlerTest} /
 * SearchEngineNnueV3NetTest opt-in pattern: the flag routes moves through the PUCT searcher with
 * the committed policy prior and returns a <b>legal</b> move; a missing artifact degrades to the
 * GoBot path with a warning instead of crashing (the EVAL=NNUEV3 precedent); and {@code
 * MCTS_MOVE_MILLIS} bounds the per-move wall clock.
 */
public class MctsLiveSearchTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * A 12x12 mid-game wire snapshot (capitalized keys, integer Kind) with us to move as player 1.
   */
  private static ObjectNode midGameSnapshot() {
    ObjectNode snapshot = MAPPER.createObjectNode();
    snapshot.put("rows", 12);
    snapshot.put("cols", 12);
    snapshot.put("currentPlayer", 1);
    snapshot.put("movesLeft", 3);
    snapshot.put("gameOver", false);
    snapshot.set("neutralUsed", MAPPER.createArrayNode().add(false).add(false));
    // Kind values (Go iota): Empty=0, Base=2, Normal=1, Fortified=3.
    Map<String, int[]> cells = new HashMap<>();
    cells.put("0,0", new int[] {1, 2}); // p1 base
    cells.put("1,1", new int[] {1, 1});
    cells.put("2,2", new int[] {1, 1});
    cells.put("3,2", new int[] {1, 1});
    cells.put("11,11", new int[] {2, 2}); // p2 base
    cells.put("10,10", new int[] {2, 1});
    cells.put("9,9", new int[] {2, 1});
    ArrayNode board = MAPPER.createArrayNode();
    for (int r = 0; r < 12; r++) {
      ArrayNode row = MAPPER.createArrayNode();
      for (int c = 0; c < 12; c++) {
        int[] oc = cells.getOrDefault(r + "," + c, new int[] {0, 0});
        row.add(MAPPER.createObjectNode().put("Owner", oc[0]).put("Kind", oc[1]));
      }
      board.add(row);
    }
    snapshot.set("board", board);
    return snapshot;
  }

  private static String turnMessage(ObjectNode snapshot) {
    ObjectNode msg = MAPPER.createObjectNode();
    msg.put("type", "turn_change");
    msg.set("snapshot", snapshot);
    return msg.toString();
  }

  /** Run a start + one turn through a fresh handler with the given system properties set. */
  private static String playOneMove(Map<String, String> props) {
    Map<String, String> prev = new HashMap<>();
    for (Map.Entry<String, String> e : props.entrySet()) {
      prev.put(e.getKey(), System.getProperty(e.getKey()));
      System.setProperty(e.getKey(), e.getValue());
    }
    try {
      MessageSender sender = mock(MessageSender.class);
      GameLoopHandler handler = new GameLoopHandler(sender);
      handler.handleMessage(
          "{\"type\":\"multiplayer_game_start\",\"gameId\":\"g-mcts\",\"yourPlayer\":1}");
      handler.handleMessage(turnMessage(midGameSnapshot()));
      ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
      verify(sender).send(captor.capture());
      return captor.getValue();
    } finally {
      for (Map.Entry<String, String> e : prev.entrySet()) {
        if (e.getValue() == null) {
          System.clearProperty(e.getKey());
        } else {
          System.setProperty(e.getKey(), e.getValue());
        }
      }
    }
  }

  /** Assert the outgoing payload names an action that is legal in the snapshot's GoState. */
  private static void assertLegal(String sentMessage) throws Exception {
    JsonNode sent = MAPPER.readTree(sentMessage);
    GoState gs = GameLoopHandler.goStateFromSnapshot(midGameSnapshot());
    String type = sent.get("type").asText();
    boolean legal = false;
    for (Action a : gs.legalActions()) {
      if ("move".equals(type) && a instanceof MoveAction) {
        MoveAction m = (MoveAction) a;
        legal |= m.target.row == sent.get("row").asInt() && m.target.col == sent.get("col").asInt();
      } else if ("neutrals".equals(type) && a instanceof PlaceNeutralsAction) {
        PlaceNeutralsAction p = (PlaceNeutralsAction) a;
        JsonNode cells = sent.get("cells");
        legal |=
            p.pos1.row == cells.get(0).get("row").asInt()
                && p.pos1.col == cells.get(0).get("col").asInt()
                && p.pos2.row == cells.get(1).get("row").asInt()
                && p.pos2.col == cells.get(1).get("col").asInt();
      }
    }
    assertTrue(legal, "sent action must be legal in the snapshot position: " + sentMessage);
  }

  @Test
  public void mctsPathReturnsLegalMoveWithinBudget() throws Exception {
    Map<String, String> props = new HashMap<>();
    props.put("SEARCH", "MCTS");
    props.put("MCTS_PRIOR", "mcts_policy.json"); // the committed Phase 1 artifact
    props.put("MCTS_MOVE_MILLIS", "50"); // tiny test budget — env plumbing under test
    String sent = playOneMove(props);
    assertLegal(sent);
    JsonNode payload = MAPPER.readTree(sent);
    assertTrue(payload.get("nodesEvaluated").asInt() > 0, "sims must map to nodesEvaluated");
    // The 50 ms budget must be honored (default is 1000; first-sim overhead gets slack).
    assertTrue(
        payload.get("timeMs").asLong() < 900,
        "MCTS_MOVE_MILLIS=50 must bound the move, got " + payload.get("timeMs"));
  }

  @Test
  public void missingArtifactFallsBackToGobotPath() throws Exception {
    Map<String, String> props = new HashMap<>();
    props.put("SEARCH", "MCTS");
    props.put("MCTS_PRIOR", "/nonexistent/mcts_champion.json");
    // Documented behavior: warn and degrade to the GoBot search — still answers with a legal move.
    String sent = playOneMove(props);
    assertLegal(sent);
  }

  @Test
  public void configLoaderPlumbing() {
    String prev = System.getProperty("MCTS_PRIOR");
    try {
      System.setProperty("MCTS_PRIOR", "/nonexistent/mcts_champion.json");
      assertNull(GameLoopHandler.loadMctsConfig(), "missing artifact must yield no config");
      System.setProperty("MCTS_PRIOR", "mcts_policy.json");
      var config = GameLoopHandler.loadMctsConfig();
      assertNotNull(config, "the committed artifact must load");
      assertNotNull(config.prior, "trained prior must be wired");
      assertNull(config.valueNet, "MCTS_VALUE unset must not enable the value head");
      assertTrue(!config.rootNoise, "live play must keep root noise OFF");
    } finally {
      if (prev == null) {
        System.clearProperty("MCTS_PRIOR");
      } else {
        System.setProperty("MCTS_PRIOR", prev);
      }
    }
  }
}
