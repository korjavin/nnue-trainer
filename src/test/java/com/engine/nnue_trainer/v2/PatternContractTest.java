package com.engine.nnue_trainer.v2;

import static org.junit.jupiter.api.Assertions.*;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import java.util.List;
import org.junit.jupiter.api.Test;

public class PatternContractTest {

  @Test
  public void testNormalizeManhattan() {
    assertEquals(7, PatternContract.normalizeManhattan(0, 0, 11, 11, 12, 12));
    assertEquals(0, PatternContract.normalizeManhattan(11, 11, 11, 11, 12, 12));
    assertEquals(3, PatternContract.normalizeManhattan(5, 5, 11, 11, 12, 12));
  }

  @Test
  public void testExtractWindows() {
    Board board = new Board(5, 5);
    board.setCell(2, 2, new Cell(1, CellKind.NORMAL));

    List<PatternContract.WindowInfo> windows = PatternContract.extractWindows(board, 1, 4, 4);

    assertEquals(1, windows.size());

    PatternContract.WindowInfo w = windows.get(0);
    assertEquals(2, w.centerRow);
    assertEquals(2, w.centerCol);

    int[] pattern = w.pattern;
    assertEquals(25, pattern.length);
    assertEquals(PatternContract.SYMBOL_NORMAL_SELF, pattern[12]);
    assertEquals(PatternContract.SYMBOL_EMPTY, pattern[0]);
  }

  @Test
  public void testExtractWindowsOob() {
    Board board = new Board(5, 5);
    board.setCell(0, 0, new Cell(2, CellKind.FORTIFIED));

    List<PatternContract.WindowInfo> windows = PatternContract.extractWindows(board, 1, 4, 4);

    assertEquals(1, windows.size());
    PatternContract.WindowInfo w = windows.get(0);
    assertEquals(0, w.centerRow);
    assertEquals(0, w.centerCol);

    int[] pattern = w.pattern;
    assertEquals(PatternContract.SYMBOL_OUT_OF_BOUNDS, pattern[0]);
    assertEquals(PatternContract.SYMBOL_FORTIFIED_OPPONENT, pattern[12]);
  }

  @Test
  public void testSkipEmptyWindows() {
    Board board = new Board(5, 5);
    List<PatternContract.WindowInfo> windows = PatternContract.extractWindows(board, 1, 4, 4);
    assertEquals(0, windows.size());
  }
}
