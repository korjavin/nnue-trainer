package com.engine.nnue_trainer.search;

import com.engine.nnue_trainer.board.Action;
import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.board.MoveGenerator;
import java.util.List;
import java.util.Random;

public class OpeningBook {
  private static final Random random = new Random();

  public static Action getRandomOpeningMove(Board board, int player, boolean canPlaceNeutral) {
    int numPieces = 0;
    for (int r = 0; r < board.rows; r++) {
      for (int c = 0; c < board.cols; c++) {
        Cell cell = board.getCell(r, c);
        if (cell != null && cell.kind != CellKind.EMPTY && cell.kind != CellKind.NEUTRAL) {
          numPieces++;
        }
      }
    }

    // Each player starts with 1 base.
    // If total pieces is less than 6, it means we are in the first 4 plies of the game
    // (since each move places 1 piece).
    if (numPieces < 6) {
      List<Action> actions = MoveGenerator.getLegalActions(player, board, canPlaceNeutral);
      if (!actions.isEmpty()) {
        return actions.get(random.nextInt(actions.size()));
      }
    }

    return null;
  }
}
