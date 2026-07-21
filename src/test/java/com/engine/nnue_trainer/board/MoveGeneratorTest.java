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

    long placeNeutralsCount =
        actions.stream().filter(a -> a instanceof PlaceNeutralsAction).count();
    assertEquals(3, placeNeutralsCount);

    assertTrue(actions.contains(new PlaceNeutralsAction(new Pos(0, 0), new Pos(0, 1))));
    assertTrue(actions.contains(new PlaceNeutralsAction(new Pos(0, 0), new Pos(0, 2))));
    assertTrue(actions.contains(new PlaceNeutralsAction(new Pos(0, 1), new Pos(0, 2))));
  }

  @Test
  void testInitialMoves() {
    Board board = new Board(5, 5);
    board.setCell(0, 0, new Cell(1, CellKind.BASE));

    // Test initial moves on turn 1
    List<Action> actions = MoveGenerator.getLegalActions(1, board, false);

    // Only 3 adjacent valid cells: (0, 1), (1, 0), (1, 1)
    assertEquals(3, actions.size());
    assertTrue(actions.contains(new MoveAction(new Pos(0, 1))));
    assertTrue(actions.contains(new MoveAction(new Pos(1, 0))));
    assertTrue(actions.contains(new MoveAction(new Pos(1, 1))));
  }

  @Test
  void testNoMovesWhenBaseCaptured() {
    Board board = new Board(5, 5);
    // Base is captured by opponent
    board.setCell(0, 0, new Cell(2, CellKind.BASE));
    // Player has some isolated normal pieces
    board.setCell(1, 1, new Cell(1, CellKind.NORMAL));
    board.setCell(2, 2, new Cell(1, CellKind.NORMAL));

    List<Action> actions = MoveGenerator.getLegalActions(1, board, false);

    // Expected to have no moves since there is no base connection
    assertTrue(actions.isEmpty());
  }

  @Test
  void testNeutralPlacementDisabled() {
    Board board = new Board(3, 3);
    board.setCell(0, 0, new Cell(1, CellKind.NORMAL));
    board.setCell(0, 1, new Cell(1, CellKind.NORMAL));

    List<Action> actions = MoveGenerator.getLegalActions(1, board, false);

    long placeNeutralsCount =
        actions.stream().filter(a -> a instanceof PlaceNeutralsAction).count();
    assertEquals(0, placeNeutralsCount);
  }
}
