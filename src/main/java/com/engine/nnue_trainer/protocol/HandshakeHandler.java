package com.engine.nnue_trainer.protocol;

import com.engine.nnue_trainer.protocol.messages.BaseMessage;
import com.engine.nnue_trainer.protocol.messages.BotWantedMessage;
import com.engine.nnue_trainer.protocol.messages.JoinLobbyMessage;
import com.engine.nnue_trainer.protocol.messages.WelcomeMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public class HandshakeHandler {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final MessageSender messageSender;

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
}
