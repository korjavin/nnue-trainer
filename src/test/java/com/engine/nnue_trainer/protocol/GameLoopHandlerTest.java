package com.engine.nnue_trainer.protocol;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.MoveAction;
import com.engine.nnue_trainer.board.Pos;
import com.engine.nnue_trainer.search.SearchEngine;
import com.engine.nnue_trainer.search.SearchResult;
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

  @Test
  public void testUsesInjectedSearchEngine() {
    messageSender = mock(MessageSender.class);
    // Default search is now the GoBot engine; opt into the injected negamax engine for this test.
    String prev = System.getProperty("SEARCH");
    System.setProperty("SEARCH", "NEGAMAX");
    try {
      gameLoopHandler =
          new GameLoopHandler(
              messageSender,
              new SearchEngine() {
                @Override
                public SearchResult findBestActionWithTimeLimitUsingModel(
                    Board board, int player, long timeLimitMs, boolean canPlaceNeutral) {
                  return new SearchResult(new MoveAction(new Pos(1, 2)), 1.0f, 1, 1, 1);
                }
              });

      gameLoopHandler.handleMessage(
          "{\"type\":\"multiplayer_game_start\",\"gameId\":\"game-123\",\"yourPlayer\":1,"
              + "\"snapshot\":{"
              + "  \"rows\":3,\"cols\":3,"
              + "  \"currentPlayer\":1,"
              + "  \"movesLeft\":3,"
              + "  \"gameOver\":false,"
              + "  \"neutralUsed\":[false,false],"
              + "  \"board\":["
              + "    [{\"Owner\":1,\"Kind\":2},{\"Owner\":0,\"Kind\":0},{\"Owner\":0,\"Kind\":0}],"
              + "    [{\"Owner\":0,\"Kind\":0},{\"Owner\":0,\"Kind\":0},{\"Owner\":0,\"Kind\":0}],"
              + "    [{\"Owner\":0,\"Kind\":0},{\"Owner\":0,\"Kind\":0},{\"Owner\":2,\"Kind\":2}]"
              + "  ]"
              + "}}");

      ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
      verify(messageSender).send(messageCaptor.capture());
      String sentMessage = messageCaptor.getValue();
      assertTrue(sentMessage.contains("\"row\":1"));
      assertTrue(sentMessage.contains("\"col\":2"));
    } finally {
      if (prev == null) {
        System.clearProperty("SEARCH");
      } else {
        System.setProperty("SEARCH", prev);
      }
    }
  }
}
