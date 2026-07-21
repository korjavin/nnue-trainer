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

public class HandshakeHandler {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final MessageSender messageSender;

  private long lastChallengeTime = 0;

  public HandshakeHandler(MessageSender messageSender) {
    this.messageSender = messageSender;
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

  private void handleWelcome(WelcomeMessage welcomeMessage) {
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
    String challengerMode = System.getenv("CHALLENGER_MODE");
    if (challengerMode == null) {
      challengerMode = System.getProperty("CHALLENGER_MODE");
    }
    if (!"true".equalsIgnoreCase(challengerMode)) {
      return;
    }

    long currentTime = System.currentTimeMillis();
    if (currentTime - lastChallengeTime < 10000) {
      return; // Rate limit challenges to at most once every 10 seconds
    }

    if (usersUpdateMessage.getUsers() == null) {
      return;
    }

    for (UsersUpdateMessage.User user : usersUpdateMessage.getUsers()) {
      if (user.getUsername() == null) {
        continue;
      }

      String username = user.getUsername().toLowerCase();
      if (!user.isInGame() && (username.contains("go") || username.startsWith("bot"))) {
        ChallengeMessage challengeMessage = new ChallengeMessage(user.getId(), 12, 12);
        try {
          String jsonResponse = objectMapper.writeValueAsString(challengeMessage);
          messageSender.send(jsonResponse);
          lastChallengeTime = currentTime;
          System.out.println("Sent challenge to " + user.getUsername());
          break; // Only challenge one user at a time
        } catch (JsonProcessingException e) {
          System.err.println("Error serializing challenge message: " + e.getMessage());
        }
      }
    }
  }
}
