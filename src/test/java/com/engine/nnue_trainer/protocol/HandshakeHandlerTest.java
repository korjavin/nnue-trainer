package com.engine.nnue_trainer.protocol;

import static org.junit.jupiter.api.Assertions.assertTrue;
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
    assertTrue(sentMessage.contains("\"type\":\"join_lobby\""));
    assertTrue(sentMessage.contains("\"lobbyId\":\"lobby-5678\""));
  }

  @Test
  public void testHandleUnknownMessage() {
    String unknownJson = "{\"type\":\"unknown\"}";
    handshakeHandler.handleMessage(unknownJson);

    verify(messageSender, never()).send(anyString());
  }

  @Test
  public void testHandleUsersUpdateMessageEmpty() {
    String usersUpdateJson = "{\"type\":\"users_update\",\"users\":[]}";
    handshakeHandler.handleMessage(usersUpdateJson);

    verify(messageSender, never()).send(anyString());
  }

  @Test
  public void testHandleUsersUpdateMessageChallengesGoBot() {
    String usersUpdateJson =
        "{\"type\":\"users_update\",\"users\":[{\"userId\":\"user-1\",\"username\":\"GoBot\",\"inGame\":false}]}";
    handshakeHandler.handleMessage(usersUpdateJson);

    ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
    verify(messageSender, times(1)).send(messageCaptor.capture());

    String sentMessage = messageCaptor.getValue();
    assertTrue(sentMessage.contains("\"type\":\"challenge\""));
    assertTrue(sentMessage.contains("\"targetUserId\":\"user-1\""));
    assertTrue(sentMessage.contains("\"rows\":12"));
    assertTrue(sentMessage.contains("\"cols\":12"));
  }

  @Test
  public void testHandleUsersUpdateMessageRateLimits() {
    String usersUpdateJson =
        "{\"type\":\"users_update\",\"users\":[{\"userId\":\"user-1\",\"username\":\"GoBot\",\"inGame\":false}]}";

    // First message should trigger challenge
    handshakeHandler.handleMessage(usersUpdateJson);
    verify(messageSender, times(1)).send(anyString());

    // Second message immediately after should be rate limited
    handshakeHandler.handleMessage(usersUpdateJson);
    verify(messageSender, times(1)).send(anyString()); // Still only 1 invocation
  }

  @Test
  public void testHandleChallengeReceived() {
    String challengeJson =
        "{\"type\":\"challenge_received\",\"challengeId\":\"chall-123\",\"fromUsername\":\"challenger\"}";
    handshakeHandler.handleMessage(challengeJson);

    ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
    verify(messageSender, times(1)).send(messageCaptor.capture());

    String sent = messageCaptor.getValue();
    assertTrue(sent.contains("\"type\":\"accept_challenge\""));
    assertTrue(sent.contains("\"challengeId\":\"chall-123\""));
  }

  @Test
  public void testHandleInvalidJson() {
    String invalidJson = "invalid json";
    handshakeHandler.handleMessage(invalidJson);

    verify(messageSender, never()).send(anyString());
  }
}
