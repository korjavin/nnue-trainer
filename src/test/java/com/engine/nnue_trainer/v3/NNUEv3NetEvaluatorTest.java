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

/**
 * {@link NNUEv3NetEvaluator}: forward-pass arithmetic (sparse accumulate → ReLU → dot) and the
 * load-time validation that keeps a malformed weights file from turning into an AIOOBE or a silent
 * NaN mid-search.
 */
public class NNUEv3NetEvaluatorTest {

  private static final int F = NNUEv3Accumulator.FEATURES;

  private static Board board() {
    Board b = new Board(12, 12);
    b.setCell(0, 0, new Cell(1, CellKind.BASE));
    b.setCell(11, 11, new Cell(2, CellKind.BASE));
    b.setCell(3, 4, new Cell(1, CellKind.NORMAL));
    b.setCell(5, 6, new Cell(2, CellKind.NORMAL));
    return b;
  }

  @Test
  public void reluGatesNegativeHiddenUnits() {
    // Two hidden units, no input weights: h0 pre-activation +2 (passes), h1 -3 (gated).
    double[][] w1 = new double[2][F];
    NNUEv3NetEvaluator ev =
        new NNUEv3NetEvaluator(w1, new double[] {2.0, -3.0}, new double[] {10.0, 100.0}, 7.0);
    assertEquals(2.0 * 10.0 + 7.0, ev.evaluate(board(), 1), 1e-12);
    assertEquals(2, ev.hidden());
  }

  @Test
  public void sumsExactlyTheActiveColumns() {
    // One hidden unit, weight 1 on every NORMAL_SELF slot, bias 0, output weight 1: the value is
    // the count of the mover's NORMAL stones. Wrong index math changes the count.
    double[][] w1 = new double[1][F];
    for (int r = 0; r < 12; r++) {
      for (int c = 0; c < 12; c++) {
        w1[0][NNUEv3Accumulator.idx(r, c, PatternContract.NORMAL_SELF)] = 1.0;
      }
    }
    NNUEv3NetEvaluator ev = new NNUEv3NetEvaluator(w1, new double[] {0.0}, new double[] {1.0}, 0.0);
    Board b = board();
    b.setCell(7, 7, new Cell(1, CellKind.NORMAL));
    assertEquals(2.0, ev.evaluate(b, 1), 1e-12, "player 1 has 2 NORMAL stones");
    assertEquals(1.0, ev.evaluate(b, 2), 1e-12, "player 2 has 1");
  }

  @Test
  public void rejectsNon12x12() {
    NNUEv3NetEvaluator ev =
        new NNUEv3NetEvaluator(new double[1][F], new double[1], new double[1], 0.0);
    assertThrows(IllegalArgumentException.class, () -> ev.evaluate(new Board(8, 8), 1));
  }

  @Test
  public void constructorRejectsShapeMismatch() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new NNUEv3NetEvaluator(new double[2][F], new double[1], new double[1], 0.0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new NNUEv3NetEvaluator(new double[1][F], new double[1], new double[2], 0.0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new NNUEv3NetEvaluator(
                new double[][] {new double[7]}, new double[1], new double[1], 0.0));
  }

  @Test
  public void loadsAndEvaluatesAWellFormedFile() throws Exception {
    NNUEv3NetEvaluator ev = NNUEv3NetEvaluator.load(write(net(2, "1.0,-3.0", "10.0,100.0", "7.0")));
    // Every w1 entry is 0 below, so h0 = 1.0 (passes ReLU), h1 = -3.0 (gated).
    assertEquals(1.0 * 10.0 + 7.0, ev.evaluate(board(), 1), 1e-12);
  }

  @Test
  public void loadRejectsMalformedFiles() throws Exception {
    // missing / wrong-typed top-level members
    assertThrows(IOException.class, () -> NNUEv3NetEvaluator.load(write("{}")));
    assertThrows(IOException.class, () -> NNUEv3NetEvaluator.load(write("{\"meta\":[],\"b2\":0}")));
    assertThrows(
        IOException.class,
        () ->
            NNUEv3NetEvaluator.load(write(net(2, "1,1", "1,1", "0").replace("\"w1\"", "\"W1\""))));
    assertThrows(
        IOException.class,
        () ->
            NNUEv3NetEvaluator.load(write(net(2, "1,1", "1,1", "0").replace("\"b1\"", "\"B1\""))));
    assertThrows(
        IOException.class,
        () ->
            NNUEv3NetEvaluator.load(write(net(2, "1,1", "1,1", "0").replace("\"w2\"", "\"W2\""))));
    assertThrows(
        IOException.class,
        () ->
            NNUEv3NetEvaluator.load(write(net(2, "1,1", "1,1", "0").replace("\"b2\"", "\"B2\""))));

    // meta disagreements
    assertThrows(
        IOException.class,
        () ->
            NNUEv3NetEvaluator.load(
                write(net(2, "1,1", "1,1", "0").replace("\"features\":1152", "\"features\":576"))));
    assertThrows(
        IOException.class,
        () ->
            NNUEv3NetEvaluator.load(
                write(net(2, "1,1", "1,1", "0").replace("\"hidden\":2", "\"hidden\":0"))));

    // b1/w2 length must equal hidden; w1 must have `hidden` rows of `features`
    assertThrows(IOException.class, () -> NNUEv3NetEvaluator.load(write(net(2, "1", "1,1", "0"))));
    assertThrows(IOException.class, () -> NNUEv3NetEvaluator.load(write(net(2, "1,1", "1", "0"))));
    assertThrows(
        IOException.class,
        () ->
            NNUEv3NetEvaluator.load(
                write(
                    "{\"meta\":{\"hidden\":1,\"features\":1152},\"w1\":[[1,2,3]],\"b1\":[0],"
                        + "\"w2\":[1],\"b2\":0}")));
    assertThrows(
        IOException.class,
        () ->
            NNUEv3NetEvaluator.load(
                write(
                    "{\"meta\":{\"hidden\":1,\"features\":1152},\"w1\":{},\"b1\":[0],"
                        + "\"w2\":[1],\"b2\":0}")));

    // non-finite values anywhere
    assertThrows(
        IOException.class, () -> NNUEv3NetEvaluator.load(write(net(2, "1e999,1", "1,1", "0"))));
    assertThrows(
        IOException.class, () -> NNUEv3NetEvaluator.load(write(net(2, "1,1", "1,1e999", "0"))));
    assertThrows(
        IOException.class, () -> NNUEv3NetEvaluator.load(write(net(2, "1,1", "1,1", "1e999"))));
    assertThrows(
        IOException.class,
        () ->
            NNUEv3NetEvaluator.load(write(net(2, "1,1", "1,1", "0").replace("0.0,0.0", "0.0,x"))));

    // and a file that is not there at all
    assertThrows(
        IOException.class, () -> NNUEv3NetEvaluator.load(Path.of("target/no-such-net.json")));
  }

  @Test
  public void committedFixtureWeightsLoad() throws Exception {
    Path synth = Path.of("src", "test", "resources", "v3", "net_synth_weights.json");
    assertTrue(Files.exists(synth), "synthetic net fixture must be committed");
    assertTrue(NNUEv3NetEvaluator.load(synth).hidden() > 0);
  }

  /** A weights doc with {@code hidden} all-zero w1 rows and the given b1/w2/b2 literals. */
  private static String net(int hidden, String b1, String w2, String b2) {
    StringBuilder rows = new StringBuilder();
    String zeros = "0.0,".repeat(F - 1) + "0.0";
    for (int h = 0; h < hidden; h++) {
      rows.append(h == 0 ? "" : ",").append('[').append(zeros).append(']');
    }
    return "{\"meta\":{\"arch\":\"1152-"
        + hidden
        + "-1\",\"hidden\":"
        + hidden
        + ",\"activation\":\"relu\",\"features\":1152},\"w1\":["
        + rows
        + "],\"b1\":["
        + b1
        + "],\"w2\":["
        + w2
        + "],\"b2\":"
        + b2
        + "}";
  }

  private static Path write(String json) throws IOException {
    Path p = Files.createTempFile("v3net", ".json");
    p.toFile().deleteOnExit();
    Files.writeString(p, json);
    return p;
  }
}
