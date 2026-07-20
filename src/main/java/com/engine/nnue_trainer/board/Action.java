package com.engine.nnue_trainer.board;

public abstract class Action {
  public enum ActionType {
    MOVE,
    PLACE_NEUTRALS
  }

  public final ActionType type;

  protected Action(ActionType type) {
    this.type = type;
  }
}
