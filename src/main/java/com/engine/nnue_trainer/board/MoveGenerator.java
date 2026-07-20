package com.engine.nnue_trainer.board;

import java.util.ArrayList;
import java.util.List;

public class MoveGenerator {

  public static List<Action> getLegalActions(int player, Board board, boolean canPlaceNeutral) {
    List<Action> actions = new ArrayList<>();

    // Generate MoveActions
    for (int r = 0; r < board.getRows(); r++) {
      for (int c = 0; c < board.getCols(); c++) {
        Pos target = new Pos(r, c);
        if (MoveValidator.isValidMove(player, target, board)) {
          actions.add(new MoveAction(target));
        }
      }
    }

    // Generate PlaceNeutralsActions
    if (canPlaceNeutral) {
      List<Pos> ownNormalCells = new ArrayList<>();
      for (int r = 0; r < board.getRows(); r++) {
        for (int c = 0; c < board.getCols(); c++) {
          Cell cell = board.getCell(r, c);
          if (cell != null && cell.owner == player && cell.kind == CellKind.NORMAL) {
            ownNormalCells.add(new Pos(r, c));
          }
        }
      }

      // Generate all unique pairs
      for (int i = 0; i < ownNormalCells.size(); i++) {
        for (int j = i + 1; j < ownNormalCells.size(); j++) {
          actions.add(new PlaceNeutralsAction(ownNormalCells.get(i), ownNormalCells.get(j)));
        }
      }
    }

    return actions;
  }
}
