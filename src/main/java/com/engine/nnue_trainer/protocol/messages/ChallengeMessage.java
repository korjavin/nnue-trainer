package com.engine.nnue_trainer.protocol.messages;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ChallengeMessage extends BaseMessage {
  private String opponentId;
  private int rows;
  private int cols;

  public ChallengeMessage() {
    setType("challenge");
  }

  public ChallengeMessage(String opponentId, int rows, int cols) {
    setType("challenge");
    this.opponentId = opponentId;
    this.rows = rows;
    this.cols = cols;
  }

  public String getOpponentId() {
    return opponentId;
  }

  public void setOpponentId(String opponentId) {
    this.opponentId = opponentId;
  }

  public int getRows() {
    return rows;
  }

  public void setRows(int rows) {
    this.rows = rows;
  }

  public int getCols() {
    return cols;
  }

  public void setCols(int cols) {
    this.cols = cols;
  }
}
