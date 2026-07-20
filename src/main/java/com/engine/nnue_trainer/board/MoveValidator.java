package com.engine.nnue_trainer.board;

public class MoveValidator {
  public static boolean[] connected(int player, Board board) {
    return new boolean[board.rows * board.cols];
  }

  public static boolean isValidMove(int player, Pos target, Board board) {
    return false;
  }
}
