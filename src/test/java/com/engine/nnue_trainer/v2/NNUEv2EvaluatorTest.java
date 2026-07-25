package com.engine.nnue_trainer.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Forward-pass sanity for the opt-in v2 evaluator (synthetic weights always; real weights gated).
 */
public class NNUEv2EvaluatorTest {

  private static final Path DICT = Path.of("python", "v2", "nnue_v2_dictionary.json");
  private static final Path WEIGHTS = Path.of("python", "v2", "nnue_v2_weights.json");

  /** Deterministic non-trivial synthetic weights so the whole pipe runs without the 344MB blob. */
  private static NNUEv2Evaluator synthetic(int w) throws Exception {
    PatternDictionary dict = PatternDictionary.load(DICT);
    int n = dict.numPatterns();
    float[][] stm = new float[n][w];
    float[][] nstm = new float[n][w];
    for (int id = 0; id < n; id++) {
      for (int i = 0; i < w; i++) {
        stm[id][i] = (float) (((id * 7 + i) % 5) - 2) * 0.1f;
        nstm[id][i] = (float) (((id * 3 + i) % 5) - 2) * 0.1f;
      }
    }
    int in = 2 * w + 14;
    float[][] l1 = fill(16, in, 0.05f);
    float[] l1b = bias(16, 0.1f);
    float[][] l2 = fill(32, 16, 0.05f);
    float[] l2b = bias(32, -0.1f);
    float[][] l3 = fill(1, 32, 0.1f);
    float[] l3b = bias(1, 0.0f);
    return new NNUEv2Evaluator(dict, stm, nstm, l1, l1b, l2, l2b, l3, l3b);
  }

  private static float[][] fill(int out, int in, float base) {
    float[][] m = new float[out][in];
    for (int o = 0; o < out; o++) {
      for (int i = 0; i < in; i++) {
        m[o][i] = base * (((o + i) % 3) - 1);
      }
    }
    return m;
  }

  private static float[] bias(int out, float v) {
    float[] b = new float[out];
    for (int o = 0; o < out; o++) {
      b[o] = v;
    }
    return b;
  }

  @Test
  public void syntheticForwardIsFiniteAndDeterministic() throws Exception {
    NNUEv2Evaluator ev = synthetic(4);
    Board b = board(9, 9);
    b.setCell(2, 2, new Cell(1, CellKind.NORMAL));
    b.setCell(2, 3, new Cell(1, CellKind.NORMAL));
    b.setCell(6, 6, new Cell(2, CellKind.NORMAL));
    float v1 = ev.evaluate(b, 1);
    float v2 = ev.evaluate(b, 1);
    assertTrue(Float.isFinite(v1), "eval must be finite");
    assertEquals(v1, v2, 0.0f, "eval must be deterministic");
  }

  @Test
  public void realWeightsWinningBeatsLosing() throws Exception {
    assumeTrue(
        Files.exists(WEIGHTS), "344MB weights blob absent (regen: export_weights.py) — skip");
    NNUEv2Evaluator ev = NNUEv2Evaluator.load(WEIGHTS, DICT);

    // Player 1 (STM) crushing: many player-1 pieces, opponent reduced to a lone base.
    Board winning = board(12, 12);
    winning.setCell(11, 11, new Cell(2, CellKind.BASE)); // opponent base only
    for (int r = 1; r <= 6; r++) {
      for (int c = 1; c <= 6; c++) {
        winning.setCell(r, c, new Cell(1, CellKind.NORMAL));
      }
    }

    // Mirror image: opponent crushing, STM (player 1) reduced to a lone base.
    Board losing = board(12, 12);
    losing.setCell(0, 0, new Cell(1, CellKind.BASE)); // STM base only
    for (int r = 5; r <= 10; r++) {
      for (int c = 5; c <= 10; c++) {
        losing.setCell(r, c, new Cell(2, CellKind.NORMAL));
      }
    }

    float win = ev.evaluate(winning, 1);
    float lose = ev.evaluate(losing, 1);
    System.out.printf("[v2-sanity] winning=%.4f losing=%.4f%n", win, lose);
    assertTrue(Float.isFinite(win) && Float.isFinite(lose), "evals must be finite");
    assertTrue(win > lose, "clearly-winning position must score higher than clearly-losing one");
  }

  private static Board board(int rows, int cols) {
    Board b = new Board(rows, cols);
    for (int r = 0; r < rows; r++) {
      for (int c = 0; c < cols; c++) {
        b.setCell(r, c, new Cell(0, CellKind.EMPTY));
      }
    }
    return b;
  }
}
