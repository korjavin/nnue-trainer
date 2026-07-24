package com.engine.nnue_trainer.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import com.engine.nnue_trainer.board.Action;
import com.engine.nnue_trainer.board.MoveAction;
import com.engine.nnue_trainer.search.gobot.GoBotSearcher;
import com.engine.nnue_trainer.search.gobot.GoResult;
import com.engine.nnue_trainer.search.gobot.GoState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Fix A: env-gated exploration adapted into the LIVE GoBot challenger ({@link GameLoopHandler}).
 */
public class ChallengerExploreTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  // Player 1 owns base(0,0) + a normal cell at (0,1), which voids the opening book → the search
  // runs and returns a real (depth>0) result with alternatives to sample from.
  private static final String MIDGAME =
      "{\"type\":\"multiplayer_game_start\",\"gameId\":\"g\",\"yourPlayer\":1,"
          + "\"snapshot\":{\"rows\":3,\"cols\":3,\"currentPlayer\":1,\"movesLeft\":3,"
          + "\"gameOver\":false,\"neutralUsed\":[false,false],\"board\":["
          + "[{\"Owner\":1,\"Kind\":2},{\"Owner\":1,\"Kind\":1},{\"Owner\":0,\"Kind\":0}],"
          + "[{\"Owner\":0,\"Kind\":0},{\"Owner\":0,\"Kind\":0},{\"Owner\":0,\"Kind\":0}],"
          + "[{\"Owner\":0,\"Kind\":0},{\"Owner\":0,\"Kind\":0},{\"Owner\":2,\"Kind\":2}]]}}";

  // Fresh board: player 1 owns only its base → the opening book fires (book move, depth 0).
  private static final String OPENING =
      "{\"type\":\"multiplayer_game_start\",\"gameId\":\"g\",\"yourPlayer\":1,"
          + "\"snapshot\":{\"rows\":3,\"cols\":3,\"currentPlayer\":1,\"movesLeft\":3,"
          + "\"gameOver\":false,\"neutralUsed\":[false,false],\"board\":["
          + "[{\"Owner\":1,\"Kind\":2},{\"Owner\":0,\"Kind\":0},{\"Owner\":0,\"Kind\":0}],"
          + "[{\"Owner\":0,\"Kind\":0},{\"Owner\":0,\"Kind\":0},{\"Owner\":0,\"Kind\":0}],"
          + "[{\"Owner\":0,\"Kind\":0},{\"Owner\":0,\"Kind\":0},{\"Owner\":2,\"Kind\":2}]]}}";

  @AfterEach
  public void clearProps() {
    System.clearProperty("CHALLENGER_EXPLORE");
    System.clearProperty("CHALLENGER_EXPLORE_TEMP");
    System.clearProperty("CHALLENGER_EXPLORE_SEED");
  }

  /** Drive one message through a fresh handler and return the sent action's (row,col), or null. */
  private static int[] playedCell(String message) throws Exception {
    MessageSender sender = mock(MessageSender.class);
    new GameLoopHandler(sender).handleMessage(message);
    ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
    verify(sender, times(1)).send(cap.capture());
    JsonNode sent = MAPPER.readTree(cap.getValue());
    assertEquals("move", sent.get("type").asText());
    return new int[] {sent.get("row").asInt(), sent.get("col").asInt()};
  }

  @Test
  public void off_playsPlainChooseNodeBudgetBestMove() throws Exception {
    // OFF (default) must equal the deterministic chooseNodeBudget best action.
    JsonNode snapshot = MAPPER.readTree(MIDGAME).get("snapshot");
    GoState gs = GameLoopHandler.goStateFromSnapshot(snapshot);
    GoResult r = GoBotSearcher.chooseNodeBudget(gs, 60000L);
    MoveAction best = (MoveAction) r.action;

    int[] cell = playedCell(MIDGAME);
    assertEquals(best.target.row, cell[0]);
    assertEquals(best.target.col, cell[1]);
  }

  @Test
  public void on_fixedSeed_isReproducibleAcrossConstructions() throws Exception {
    System.setProperty("CHALLENGER_EXPLORE", "true");
    System.setProperty("CHALLENGER_EXPLORE_TEMP", "1.0");
    System.setProperty("CHALLENGER_EXPLORE_SEED", "1");

    int[] first = playedCell(MIDGAME);
    int[] second = playedCell(MIDGAME);
    assertEquals(first[0], second[0]);
    assertEquals(first[1], second[1]);
  }

  @Test
  public void on_openingPosition_yieldsLegalPlacement() throws Exception {
    System.setProperty("CHALLENGER_EXPLORE", "true");
    System.setProperty("CHALLENGER_EXPLORE_SEED", "7");

    JsonNode snapshot = MAPPER.readTree(OPENING).get("snapshot");
    GoState gs = GameLoopHandler.goStateFromSnapshot(snapshot);
    Set<String> legal = new HashSet<>();
    for (Action a : gs.legalActions()) {
      if (a instanceof MoveAction) {
        MoveAction m = (MoveAction) a;
        legal.add(m.target.row + "," + m.target.col);
      }
    }

    int[] cell = playedCell(OPENING);
    assertTrue(
        legal.contains(cell[0] + "," + cell[1]),
        "explored opening move must be a legal placement: " + cell[0] + "," + cell[1]);
  }
}
