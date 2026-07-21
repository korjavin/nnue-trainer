package com.engine.nnue_trainer.nnue;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import org.junit.jupiter.api.Test;

public class AccumulatorTest {
  @Test
  public void testAccumulatorUpdate() {
    NNUEModel model = NNUEModel.createDefault();
    Board board = new Board(12, 12);

    // Setup initial board
    board.setCell(0, 0, new Cell(1, CellKind.NORMAL));
    board.setCell(1, 1, new Cell(2, CellKind.FORTIFIED));

    Accumulator accum1 = new Accumulator();
    accum1.init(board, 1, model);

    // Copy the board to modify it
    Board newBoard = new Board(12, 12);
    for (int r = 0; r < 12; r++) {
      for (int c = 0; c < 12; c++) {
        Cell cell = board.getCell(r, c);
        if (cell != null) {
          newBoard.setCell(r, c, new Cell(cell.owner, cell.kind));
        }
      }
    }

    // Move piece
    int oldState00 = Accumulator.getFeatureState(newBoard.getCell(0, 0), 1);
    int oldState22 = Accumulator.getFeatureState(newBoard.getCell(2, 2), 1);

    newBoard.setCell(0, 0, new Cell(0, CellKind.EMPTY));
    newBoard.setCell(2, 2, new Cell(1, CellKind.NORMAL));

    int newState00 = Accumulator.getFeatureState(newBoard.getCell(0, 0), 1);
    int newState22 = Accumulator.getFeatureState(newBoard.getCell(2, 2), 1);

    // Incrementally update accum1
    accum1.update(0, 0, oldState00, newState00, model);
    accum1.update(2, 2, oldState22, newState22, model);

    // Recompute from scratch on newBoard
    Accumulator accum2 = new Accumulator();
    accum2.init(newBoard, 1, model);

    assertArrayEquals(accum2.getClippedReLUActivation(), accum1.getClippedReLUActivation(), 1e-5f);
  }
}
