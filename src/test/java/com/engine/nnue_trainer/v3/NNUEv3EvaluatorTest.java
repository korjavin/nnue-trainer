package com.engine.nnue_trainer.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.v2.PatternContract;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Load validation + arithmetic of the v3 leaf evaluator. */
public class NNUEv3EvaluatorTest {

  @TempDir Path tmp;
  private int fileSeq;

  private Path write(String json) throws IOException {
    Path p = tmp.resolve("w" + (fileSeq++) + ".json");
    Files.writeString(p, json);
    return p;
  }

  private static String weightsJson(String bias, String weightEntries) {
    return "{\"meta\":{\"bias\":"
        + bias
        + ",\"n_features_total\":1152},\"weights\":{"
        + weightEntries
        + "}}";
  }

  private static Board board() {
    Board b = new Board(12, 12);
    b.setCell(0, 0, new Cell(1, CellKind.BASE));
    b.setCell(3, 4, new Cell(1, CellKind.NORMAL));
    return b;
  }

  @Test
  public void testEvaluatesToHandComputedScore() throws IOException {
    // Two cells we control, everything else EMPTY (state 0) -> 142 empty cells at weight 3.
    int base = NNUEv3Accumulator.idx(0, 0, PatternContract.BASE_SELF);
    int normal = NNUEv3Accumulator.idx(3, 4, PatternContract.NORMAL_SELF);
    StringBuilder entries = new StringBuilder();
    for (int r = 0; r < 12; r++) {
      for (int c = 0; c < 12; c++) {
        entries
            .append('"')
            .append(NNUEv3Accumulator.idx(r, c, PatternContract.EMPTY))
            .append("\":3,");
      }
    }
    entries.append('"').append(base).append("\":100,");
    entries.append('"').append(normal).append("\":7");

    NNUEv3Evaluator ev = NNUEv3Evaluator.load(write(weightsJson("-1.5", entries.toString())));

    assertEquals(-1.5 + 142 * 3 + 100 + 7, ev.evaluate(board(), 1), 1e-9);
    // Same board from the other side: the two owned cells become *_OPPONENT, which has weight 0.
    assertEquals(-1.5 + 142 * 3, ev.evaluate(board(), 2), 1e-9);
  }

  @Test
  public void testMissingWeightsAreZeroAndBiasOnlyFileLoads() throws IOException {
    NNUEv3Evaluator ev = NNUEv3Evaluator.load(write(weightsJson("42", "")));
    assertEquals(42.0, ev.evaluate(board(), 1), 1e-9);
  }

  @Test
  public void testRealWeightsFileLoads() throws IOException {
    Path real = NNUEv3Evaluator.DEFAULT_WEIGHTS;
    if (!Files.exists(real)) {
      return; // repo-root weights are committed; skip rather than fail in odd working dirs
    }
    double score = NNUEv3Evaluator.load(real).evaluate(board(), 1);
    assertTrue(Double.isFinite(score), "real weights produced " + score);
  }

  @Test
  public void testMalformedInputsThrowOnLoad() throws IOException {
    // missing weights key
    assertThrows(
        IOException.class,
        () -> NNUEv3Evaluator.load(write("{\"meta\":{\"bias\":0,\"n_features_total\":1152}}")));
    // missing meta
    assertThrows(IOException.class, () -> NNUEv3Evaluator.load(write("{\"weights\":{}}")));
    // wrong type for weights
    assertThrows(
        IOException.class,
        () ->
            NNUEv3Evaluator.load(
                write("{\"meta\":{\"bias\":0,\"n_features_total\":1152},\"weights\":[]}")));
    // missing / non-numeric bias
    assertThrows(
        IOException.class,
        () -> NNUEv3Evaluator.load(write("{\"meta\":{\"n_features_total\":1152},\"weights\":{}}")));
    assertThrows(
        IOException.class,
        () ->
            NNUEv3Evaluator.load(
                write("{\"meta\":{\"bias\":\"x\",\"n_features_total\":1152},\"weights\":{}}")));
    // non-finite weight
    assertThrows(
        IOException.class, () -> NNUEv3Evaluator.load(write(weightsJson("0", "\"5\":1e999"))));
    // non-finite bias
    assertThrows(IOException.class, () -> NNUEv3Evaluator.load(write(weightsJson("1e999", ""))));
    // out-of-range and non-integer feature ids
    assertThrows(
        IOException.class, () -> NNUEv3Evaluator.load(write(weightsJson("0", "\"1152\":1"))));
    assertThrows(
        IOException.class, () -> NNUEv3Evaluator.load(write(weightsJson("0", "\"-1\":1"))));
    assertThrows(IOException.class, () -> NNUEv3Evaluator.load(write(weightsJson("0", "\"a\":1"))));
    // feature space mismatch
    assertThrows(
        IOException.class,
        () ->
            NNUEv3Evaluator.load(
                write("{\"meta\":{\"bias\":0,\"n_features_total\":576},\"weights\":{}}")));
  }

  @Test
  public void testNon12x12Rejected() throws IOException {
    NNUEv3Evaluator ev = NNUEv3Evaluator.load(write(weightsJson("0", "")));
    assertThrows(IllegalArgumentException.class, () -> ev.evaluate(new Board(9, 9), 1));
  }
}
