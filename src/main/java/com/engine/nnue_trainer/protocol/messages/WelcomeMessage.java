package com.engine.nnue_trainer.protocol.messages;

public class WelcomeMessage extends BaseMessage {
  private String userId;
  private String username;

  public WelcomeMessage() {
    setType("welcome");
  }

  public String getUserId() {
    return userId;
  }

  public void setUserId(String userId) {
    this.userId = userId;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }
}
