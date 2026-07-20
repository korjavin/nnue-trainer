package com.engine.nnue_trainer.board;

public enum CellKind {
  EMPTY(0),
  NORMAL(1),
  BASE(2),
  FORTIFIED(3),
  NEUTRAL(4);

  public final int value;

  CellKind(int value) {
    this.value = value;
  }
}
