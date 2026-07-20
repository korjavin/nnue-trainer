package com.engine.nnue_trainer.protocol.messages;

public class AcceptChallengeMessage extends BaseMessage {
  private final String challengeId;

  public AcceptChallengeMessage(String challengeId) {
    this.challengeId = challengeId;
    setType("accept_challenge");
  }

  public String getChallengeId() {
    return challengeId;
  }
}
