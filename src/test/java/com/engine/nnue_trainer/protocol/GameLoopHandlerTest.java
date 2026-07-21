package com.engine.nnue_trainer.protocol;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

public class GameLoopHandlerTest {

  private MessageSender messageSender;
  private GameLoopHandler gameLoopHandler;

  @BeforeEach
  public void setup() {
    messageSender = mock(MessageSender.class);
    gameLoopHandler = new GameLoopHandler(messageSender);
  }

  @Test
  public void testGameFlow_StartAndTurn() {
    // 1. Multiplayer Game Start
    String startJson =
        "{\"type\":\"multiplayer_game_start\",\"gameId\":\"game-123\",\"yourPlayer\":1,\"rows\":3,\"cols\":3}";
    gameLoopHandler.handleMessage(startJson);

    // 2. Turn Change snapshot with us as currentPlayer and 3 normal cells to make moves
    String turnChangeJson =
        "{"
            + "\"type\":\"turn_change\","
            + "\"snapshot\":{"
            + "  \"rows\":3,\"cols\":3,"
            + "  \"currentPlayer\":1,"
            + "  \"movesLeft\":3,"
            + "  \"gameOver\":false,"
            + "  \"winner\":0,"
            + "  \"neutralUsed\":[false,false],"
            + "  \"bases\":[{\"row\":0,\"col\":0},{\"row\":2,\"col\":2}],"
            + "  \"board\":["
            + "    [{\"Owner\":1,\"Kind\":2},{\"Owner\":0,\"Kind\":0},{\"Owner\":0,\"Kind\":0}],"
            + "    [{\"Owner\":0,\"Kind\":0},{\"Owner\":0,\"Kind\":0},{\"Owner\":0,\"Kind\":0}],"
            + "    [{\"Owner\":0,\"Kind\":0},{\"Owner\":0,\"Kind\":0},{\"Owner\":2,\"Kind\":2}]"
            + "  ]"
            + "}"
            + "}";

    gameLoopHandler.handleMessage(turnChangeJson);

    ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
    verify(messageSender, times(1)).send(messageCaptor.capture());

    String sentMessage = messageCaptor.getValue();
    assertTrue(sentMessage.contains("\"type\":\"move\""));
    assertTrue(sentMessage.contains("\"gameId\":\"game-123\""));
    assertTrue(sentMessage.contains("\"score\""));
    assertTrue(sentMessage.contains("\"depth\""));
    assertTrue(sentMessage.contains("\"nodesEvaluated\""));
    assertTrue(sentMessage.contains("\"timeMs\""));
  }
}
