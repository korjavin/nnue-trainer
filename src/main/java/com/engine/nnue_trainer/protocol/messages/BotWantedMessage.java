package com.engine.nnue_trainer.protocol.messages;

public class BotWantedMessage extends BaseMessage {
  private String lobbyId;
  private String requestId;

  public BotWantedMessage() {
    setType("bot_wanted");
  }

  public String getLobbyId() {
    return lobbyId;
  }

  public void setLobbyId(String lobbyId) {
    this.lobbyId = lobbyId;
  }

  public String getRequestId() {
    return requestId;
  }

  public void setRequestId(String requestId) {
    this.requestId = requestId;
  }
}
