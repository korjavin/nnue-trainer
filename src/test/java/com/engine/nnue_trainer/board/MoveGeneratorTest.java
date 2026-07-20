package com.engine.nnue_trainer.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class MoveGeneratorTest {

  @Test
  void testGetLegalActions_NoValidMoves() {
    Board board = new Board(5, 5);
    for (int r = 0; r < 5; r++) {
      for (int c = 0; c < 5; c++) {
        board.setCell(r, c, new Cell(0, CellKind.EMPTY));
      }
    }
    // Assuming MoveValidator.isValidMove currently returns false
    List<Action> actions = MoveGenerator.getLegalActions(1, board, false);
    assertTrue(actions.isEmpty());
  }

  @Test
  void testGetLegalActions_PlaceNeutrals() {
    Board board = new Board(3, 3);
    // Place some normal cells for player 1
    for (int r = 0; r < 3; r++) {
      for (int c = 0; c < 3; c++) {
        board.setCell(r, c, new Cell(0, CellKind.EMPTY));
      }
    }

    board.setCell(0, 0, new Cell(1, CellKind.NORMAL));
    board.setCell(0, 1, new Cell(1, CellKind.NORMAL));
    board.setCell(0, 2, new Cell(1, CellKind.NORMAL));

    List<Action> actions = MoveGenerator.getLegalActions(1, board, true);

    // Number of unique pairs for 3 cells is 3! / (2! * 1!) = 3
    long placeNeutralsCount =
        actions.stream().filter(a -> a.type == Action.ActionType.PLACE_NEUTRALS).count();
    assertEquals(3, placeNeutralsCount);

    assertTrue(actions.contains(new PlaceNeutralsAction(new Pos(0, 0), new Pos(0, 1))));
    assertTrue(actions.contains(new PlaceNeutralsAction(new Pos(0, 0), new Pos(0, 2))));
    assertTrue(actions.contains(new PlaceNeutralsAction(new Pos(0, 1), new Pos(0, 2))));
  }
}
