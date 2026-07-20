package com.engine.nnue_trainer.protocol;

import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

public class HandshakeHandlerTest {

  private MessageSender messageSender;
  private HandshakeHandler handshakeHandler;

  @BeforeEach
  public void setup() {
    messageSender = mock(MessageSender.class);
    handshakeHandler = new HandshakeHandler(messageSender);
  }

  @Test
  public void testHandleWelcome() {
    String welcomeJson = "{\"type\":\"welcome\",\"userId\":\"1234\",\"username\":\"testBot\"}";
    handshakeHandler.handleMessage(welcomeJson);

    // Welcome should not send any message back
    verify(messageSender, never()).send(anyString());
  }

  @Test
  public void testHandleBotWanted() {
    String botWantedJson =
        "{\"type\":\"bot_wanted\",\"lobbyId\":\"lobby-5678\",\"requestId\":\"req-1\"}";
    handshakeHandler.handleMessage(botWantedJson);

    ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
    verify(messageSender, times(1)).send(messageCaptor.capture());

    String sentMessage = messageCaptor.getValue();

    // Verify it contains the right type and lobbyId
    // The exact serialization might depend on Jackson settings, but should contain these at least
    assert (sentMessage.contains("\"type\":\"join_lobby\""));
    assert (sentMessage.contains("\"lobbyId\":\"lobby-5678\""));
  }

  @Test
  public void testHandleUnknownMessage() {
    String unknownJson = "{\"type\":\"unknown\"}";
    handshakeHandler.handleMessage(unknownJson);

    verify(messageSender, never()).send(anyString());
  }

  @Test
  public void testHandleInvalidJson() {
    String invalidJson = "invalid json";
    handshakeHandler.handleMessage(invalidJson);

    verify(messageSender, never()).send(anyString());
  }
}
