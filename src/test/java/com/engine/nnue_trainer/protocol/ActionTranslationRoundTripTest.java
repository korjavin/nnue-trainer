package com.engine.nnue_trainer.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.engine.nnue_trainer.board.Action;
import com.engine.nnue_trainer.board.MoveAction;
import com.engine.nnue_trainer.board.PlaceNeutralsAction;
import com.engine.nnue_trainer.board.Pos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Round-trips every {@link Action} kind through {@link GameLoopHandler#writeAction} (the live
 * action-&gt;server-move translation) and back, asserting equality. Guards the wiring the fixed-depth
 * and node-budget parity fixtures never exercised: the {@code GoResult.action -> server message}
 * step. The parse side mirrors GoBot's own server wire contract (bot_client.go {@code actionMessage}
 * / server hub): {@code {type:"move", row, col}} and {@code {type:"neutrals", cells:[...]}}.
 */
public class ActionTranslationRoundTripTest {
  private final ObjectMapper mapper = new ObjectMapper();

  /** Inverse of {@link GameLoopHandler#writeAction} — parse a server message back into an Action. */
  private static Action parseAction(JsonNode msg) {
    String type = msg.get("type").asText();
    if ("move".equals(type)) {
      return new MoveAction(new Pos(msg.get("row").asInt(), msg.get("col").asInt()));
    }
    if ("neutrals".equals(type)) {
      JsonNode cells = msg.get("cells");
      return new PlaceNeutralsAction(
          new Pos(cells.get(0).get("row").asInt(), cells.get(0).get("col").asInt()),
          new Pos(cells.get(1).get("row").asInt(), cells.get(1).get("col").asInt()));
    }
    throw new IllegalArgumentException("unknown action type: " + type);
  }

  private Action roundTrip(Action action) throws Exception {
    ObjectNode response = mapper.createObjectNode();
    GameLoopHandler.writeAction(response, mapper, action);
    // Serialize + reparse to exercise the actual wire bytes, not just the in-memory node.
    return parseAction(mapper.readTree(mapper.writeValueAsString(response)));
  }

  @Test
  public void moveActionRoundTrips() throws Exception {
    // The 3-actions-per-turn flow is three independent MOVE messages at distinct targets.
    for (Action a :
        List.of(
            new MoveAction(new Pos(0, 0)),
            new MoveAction(new Pos(3, 5)),
            new MoveAction(new Pos(7, 2)))) {
      assertEquals(a, roundTrip(a));
    }
  }

  @Test
  public void placeNeutralsActionRoundTrips() throws Exception {
    Action a = new PlaceNeutralsAction(new Pos(1, 2), new Pos(4, 6));
    Action back = roundTrip(a);
    assertEquals(a, back);
    // Assert the exact wire shape, not only equals() (which is order-insensitive for neutrals).
    ObjectNode msg = mapper.createObjectNode();
    GameLoopHandler.writeAction(msg, mapper, a);
    assertEquals("neutrals", msg.get("type").asText());
    assertEquals(1, msg.get("cells").get(0).get("row").asInt());
    assertEquals(2, msg.get("cells").get(0).get("col").asInt());
    assertEquals(4, msg.get("cells").get(1).get("row").asInt());
    assertEquals(6, msg.get("cells").get(1).get("col").asInt());
  }
}
