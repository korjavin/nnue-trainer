package com.engine.nnue_trainer.protocol.messages;

public class JoinLobbyMessage extends BaseMessage {
  private String lobbyId;

  public JoinLobbyMessage() {
    setType("join_lobby");
  }

  public JoinLobbyMessage(String lobbyId) {
    setType("join_lobby");
    this.lobbyId = lobbyId;
  }

  public String getLobbyId() {
    return lobbyId;
  }

  public void setLobbyId(String lobbyId) {
    this.lobbyId = lobbyId;
  }
}
