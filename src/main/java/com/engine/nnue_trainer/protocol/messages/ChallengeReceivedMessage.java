package com.engine.nnue_trainer.protocol.messages;

public class ChallengeReceivedMessage extends BaseMessage {
  private String challengeId;
  private String fromUsername;

  public String getChallengeId() {
    return challengeId;
  }

  public void setChallengeId(String challengeId) {
    this.challengeId = challengeId;
  }

  public String getFromUsername() {
    return fromUsername;
  }

  public void setFromUsername(String fromUsername) {
    this.fromUsername = fromUsername;
  }
}
