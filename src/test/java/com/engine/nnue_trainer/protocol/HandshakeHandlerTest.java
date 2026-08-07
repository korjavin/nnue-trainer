package com.engine.nnue_trainer.protocol;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

public class HandshakeHandlerTest {

  private MessageSender messageSender;
  private HandshakeHandler handshakeHandler;

  @BeforeEach
  public void setup() {
    System.setProperty("CHALLENGER_MODE", "true");
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
  public void testUsersUpdateNeverSendsDirectly() {
    // Cadence guard: user-list updates only cache the list. Even a flood of updates must not
    // produce a single challenge — only the periodic timer tick sends (nnue-trainer-0ui spam).
    String usersUpdateJson =
        "{\"type\":\"users_update\",\"users\":[{\"userId\":\"user-1\",\"username\":\"GoBot\",\"inGame\":false}]}";
    for (int i = 0; i < 5; i++) {
      handshakeHandler.handleMessage(usersUpdateJson);
    }
    verify(messageSender, never()).send(anyString());
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

  @Test
  public void testHandleUsersUpdateMessageDoesNotChallengeIfDisabled() {
    // Env/config is read once at construction, so set the flag BEFORE building the handler.
    System.setProperty("CHALLENGER_MODE", "false");
    HandshakeHandler disabledHandler = new HandshakeHandler(messageSender);
    String usersUpdateJson =
        "{\"type\":\"users_update\",\"users\":[{\"userId\":\"user-1\",\"username\":\"GoBot\",\"inGame\":false}]}";
    disabledHandler.handleMessage(usersUpdateJson);

    verify(messageSender, never()).send(anyString());
  }

  // ---- Timer-driven challenger tests (fully-injectable constructor) ----

  private static final String THREE_USERS =
      "{\"type\":\"users_update\",\"users\":["
          + "{\"userId\":\"a\",\"username\":\"Alice\",\"inGame\":false},"
          + "{\"userId\":\"b\",\"username\":\"Bob\",\"inGame\":false},"
          + "{\"userId\":\"c\",\"username\":\"Cara\",\"inGame\":false}]}";

  @Test
  public void testPeriodicTimerFiresChallenge() {
    ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    HandshakeHandler handler =
        new HandshakeHandler(messageSender, () -> false, scheduler, new Random(1), true, 300);

    handler.start();

    ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
    verify(scheduler)
        .scheduleAtFixedRate(taskCaptor.capture(), anyLong(), eq(300L), eq(TimeUnit.SECONDS));

    // Cache one eligible user, then run the timer task: exactly one challenge per tick.
    handler.handleMessage(
        "{\"type\":\"users_update\",\"users\":[{\"userId\":\"user-1\",\"username\":\"GoBot\",\"inGame\":false}]}");

    taskCaptor.getValue().run();

    ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
    verify(messageSender, times(1)).send(msg.capture());
    assertTrue(msg.getValue().contains("\"targetUserId\":\"user-1\""));

    // A declined/ignored challenge never wedges the loop: the next tick just challenges again.
    taskCaptor.getValue().run();
    verify(messageSender, times(2)).send(anyString());
  }

  @Test
  public void testRandomEligiblePick() {
    HandshakeHandler handler =
        new HandshakeHandler(
            messageSender,
            () -> false,
            mock(ScheduledExecutorService.class),
            new Random(42),
            true,
            300);

    handler.handleMessage(THREE_USERS);

    // Independently seeded Random predicts the pick — no magic constant.
    List<String> ids = List.of("a", "b", "c");
    String expected = ids.get(new Random(42).nextInt(ids.size()));

    handler.challengeTick();

    ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
    verify(messageSender, times(1)).send(msg.capture());
    assertTrue(msg.getValue().contains("\"targetUserId\":\"" + expected + "\""));
  }

  @Test
  public void testSkipWhileInGame() {
    HandshakeHandler handler =
        new HandshakeHandler(
            messageSender,
            () -> true,
            mock(ScheduledExecutorService.class),
            new Random(1),
            true,
            300);

    handler.handleMessage(THREE_USERS);
    handler.challengeTick();

    verify(messageSender, never()).send(anyString());
  }

  @Test
  public void testSelfExclusion() {
    HandshakeHandler handler =
        new HandshakeHandler(
            messageSender,
            () -> false,
            mock(ScheduledExecutorService.class),
            new Random(1),
            true,
            300);

    // Welcome sets selfId = "self".
    handler.handleMessage("{\"type\":\"welcome\",\"userId\":\"self\",\"username\":\"Me\"}");

    // Only self online → no challenge.
    handler.handleMessage(
        "{\"type\":\"users_update\",\"users\":[{\"userId\":\"self\",\"username\":\"Me\",\"inGame\":false}]}");
    handler.challengeTick();
    verify(messageSender, never()).send(anyString());

    // Self + one other → challenges the other.
    handler.handleMessage(
        "{\"type\":\"users_update\",\"users\":["
            + "{\"userId\":\"self\",\"username\":\"Me\",\"inGame\":false},"
            + "{\"userId\":\"other\",\"username\":\"Them\",\"inGame\":false}]}");
    handler.challengeTick();

    ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
    verify(messageSender, times(1)).send(msg.capture());
    assertTrue(msg.getValue().contains("\"targetUserId\":\"other\""));
  }

  @Test
  public void testNoEligibleNoOp() {
    HandshakeHandler handler =
        new HandshakeHandler(
            messageSender,
            () -> false,
            mock(ScheduledExecutorService.class),
            new Random(1),
            true,
            300);

    // All users already in-game → nothing eligible.
    handler.handleMessage(
        "{\"type\":\"users_update\",\"users\":["
            + "{\"userId\":\"a\",\"username\":\"Alice\",\"inGame\":true},"
            + "{\"userId\":\"b\",\"username\":\"Bob\",\"inGame\":true}]}");
    handler.challengeTick();

    verify(messageSender, never()).send(anyString());
  }
}
