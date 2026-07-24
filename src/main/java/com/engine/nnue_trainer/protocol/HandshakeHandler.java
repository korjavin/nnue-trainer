package com.engine.nnue_trainer.protocol;

import com.engine.nnue_trainer.protocol.messages.AcceptChallengeMessage;
import com.engine.nnue_trainer.protocol.messages.BaseMessage;
import com.engine.nnue_trainer.protocol.messages.BotWantedMessage;
import com.engine.nnue_trainer.protocol.messages.ChallengeMessage;
import com.engine.nnue_trainer.protocol.messages.ChallengeReceivedMessage;
import com.engine.nnue_trainer.protocol.messages.JoinLobbyMessage;
import com.engine.nnue_trainer.protocol.messages.UsersUpdateMessage;
import com.engine.nnue_trainer.protocol.messages.WelcomeMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * In challenger mode this is a TIMER-driven challenger: on a fixed cadence, when the bot is not
 * itself in a game, it picks a random eligible online player and sends a challenge. The reactive
 * path (challenge on user-list update, 10s-throttled) is kept as an immediate first attempt, but
 * the timer is the real driver so the bot never gets stuck when updates stop or a challenge is
 * declined.
 */
public class HandshakeHandler {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final MessageSender messageSender;
  private final BooleanSupplier isInGame;
  private final ScheduledExecutorService scheduler;
  private final Random random;
  private final boolean challengerMode;
  private final int intervalSec;

  // Written on the message worker thread, read on the scheduler thread.
  private volatile List<UsersUpdateMessage.User> onlineUsers = List.of();
  private volatile String selfId;

  private long lastChallengeTime = 0;

  /** Production constructor: no in-game info (never in game), env-derived config. */
  public HandshakeHandler(MessageSender messageSender) {
    this(messageSender, () -> false);
  }

  /** Production constructor: injectable in-game check, env-derived config + own scheduler/Random. */
  public HandshakeHandler(MessageSender messageSender, BooleanSupplier isInGame) {
    this(
        messageSender,
        isInGame,
        Executors.newSingleThreadScheduledExecutor(),
        new Random(),
        challengerModeFromEnv(),
        intervalSecFromEnv());
  }

  /** Fully-injectable constructor for tests. */
  public HandshakeHandler(
      MessageSender messageSender,
      BooleanSupplier isInGame,
      ScheduledExecutorService scheduler,
      Random random,
      boolean challengerMode,
      int intervalSec) {
    this.messageSender = messageSender;
    this.isInGame = isInGame;
    this.scheduler = scheduler;
    this.random = random;
    this.challengerMode = challengerMode;
    this.intervalSec = intervalSec;
  }

  public void handleMessage(String jsonMessage) {
    try {
      BaseMessage message = objectMapper.readValue(jsonMessage, BaseMessage.class);

      if (message instanceof WelcomeMessage) {
        handleWelcome((WelcomeMessage) message);
      } else if (message instanceof BotWantedMessage) {
        handleBotWanted((BotWantedMessage) message);
      } else if (message instanceof ChallengeReceivedMessage) {
        handleChallengeReceived((ChallengeReceivedMessage) message);
      } else if (message instanceof UsersUpdateMessage) {
        handleUsersUpdate((UsersUpdateMessage) message);
      }
    } catch (JsonProcessingException e) {
      // Log error or ignore unknown messages
      System.err.println("Error parsing message: " + e.getMessage());
    }
  }

  /** Begin the periodic challenge timer (no-op unless challenger mode). */
  public void start() {
    if (!challengerMode) {
      return;
    }
    // Jitter the initial delay only (period stays intervalSec) so multiple bots don't sync.
    long initialDelay = random.nextInt(Math.min(30, intervalSec) + 1);
    scheduler.scheduleAtFixedRate(this::challengeTick, initialDelay, intervalSec, TimeUnit.SECONDS);
  }

  /** Stop the periodic timer. */
  public void shutdown() {
    scheduler.shutdownNow();
  }

  private void handleWelcome(WelcomeMessage welcomeMessage) {
    selfId = welcomeMessage.getUserId();
    System.out.println("Received welcome message for user: " + welcomeMessage.getUsername());
  }

  private void handleBotWanted(BotWantedMessage botWantedMessage) {
    JoinLobbyMessage joinLobbyMessage = new JoinLobbyMessage(botWantedMessage.getLobbyId());
    try {
      String jsonResponse = objectMapper.writeValueAsString(joinLobbyMessage);
      messageSender.send(jsonResponse);
    } catch (JsonProcessingException e) {
      System.err.println("Error serializing join_lobby message: " + e.getMessage());
    }
  }

  private void handleChallengeReceived(ChallengeReceivedMessage challengeMessage) {
    System.out.println(
        "Received challenge from: "
            + challengeMessage.getFromUsername()
            + " (Challenge ID: "
            + challengeMessage.getChallengeId()
            + ")");
    AcceptChallengeMessage acceptMessage =
        new AcceptChallengeMessage(challengeMessage.getChallengeId());
    try {
      String jsonResponse = objectMapper.writeValueAsString(acceptMessage);
      messageSender.send(jsonResponse);
    } catch (JsonProcessingException e) {
      System.err.println("Error serializing accept_challenge message: " + e.getMessage());
    }
  }

  private void handleUsersUpdate(UsersUpdateMessage usersUpdateMessage) {
    List<UsersUpdateMessage.User> users = usersUpdateMessage.getUsers();
    onlineUsers = users == null ? List.of() : users;

    if (!challengerMode) {
      return;
    }
    long currentTime = System.currentTimeMillis();
    if (currentTime - lastChallengeTime < 10000) {
      return; // Reactive path throttled to at most once every 10 seconds; timer is the real driver.
    }
    lastChallengeTime = currentTime;
    attemptChallenge();
  }

  /** The scheduled task body. */
  void challengeTick() {
    attemptChallenge();
  }

  /** Pick a random eligible online player (not self, not in-game) and send a 12x12 challenge. */
  void attemptChallenge() {
    if (!challengerMode) {
      return;
    }
    if (isInGame.getAsBoolean()) {
      return;
    }
    List<UsersUpdateMessage.User> snapshot = onlineUsers;
    List<UsersUpdateMessage.User> eligible = new ArrayList<>();
    for (UsersUpdateMessage.User user : snapshot) {
      if (user.getId() != null && !user.getId().equals(selfId) && !user.isInGame()) {
        eligible.add(user);
      }
    }
    if (eligible.isEmpty()) {
      return;
    }
    UsersUpdateMessage.User target = eligible.get(random.nextInt(eligible.size()));
    ChallengeMessage challengeMessage = new ChallengeMessage(target.getId(), 12, 12);
    try {
      String jsonResponse = objectMapper.writeValueAsString(challengeMessage);
      messageSender.send(jsonResponse);
      System.out.println("Sent challenge to " + target.getUsername());
    } catch (JsonProcessingException e) {
      System.err.println("Error serializing challenge message: " + e.getMessage());
    }
  }

  private static boolean challengerModeFromEnv() {
    String value = System.getenv("CHALLENGER_MODE");
    if (value == null) {
      value = System.getProperty("CHALLENGER_MODE");
    }
    return "true".equalsIgnoreCase(value);
  }

  private static int intervalSecFromEnv() {
    String value = System.getenv("CHALLENGE_INTERVAL_SEC");
    if (value == null) {
      value = System.getProperty("CHALLENGE_INTERVAL_SEC");
    }
    if (value == null) {
      return 300;
    }
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      return 300;
    }
  }
}
