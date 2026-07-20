package com.engine.nnue_trainer.board;

import java.util.Objects;

public class MoveAction extends Action {
  public final Pos target;

  public MoveAction(Pos target) {
    super(ActionType.MOVE);
    this.target = target;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    MoveAction that = (MoveAction) o;
    return Objects.equals(target, that.target);
  }

  @Override
  public int hashCode() {
    return Objects.hash(target);
  }

  @Override
  public String toString() {
    return "MoveAction{" + "target=" + target.row + "," + target.col + "}";
  }
}
