package com.engine.nnue_trainer.v2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Byte-for-byte parity against the Python contract: the Java accumulator's
 * per-perspective (id -> count) maps must equal the committed Python-generated
 * fixture ({@code src/test/resources/v2/accumulator_parity_fixture.json}) using
 * the SAME promoted dictionary both sides consume.
 */
public class NNUEv2AccumulatorTest {

  private static final Path DICT = Path.of("python", "v2", "nnue_v2_dictionary.json");
  private static final Path FIXTURE =
      Path.of("src", "test", "resources", "v2", "accumulator_parity_fixture.json");

  @Test
  public void testParityAgainstPythonFixture() throws Exception {
    PatternDictionary dict = PatternDictionary.load(DICT);
    NNUEv2Accumulator acc = new NNUEv2Accumulator(dict, null, null, 8);

    JsonNode boards = new ObjectMapper().readTree(FIXTURE.toFile());
    int nonEmptyBoards = 0;
    for (JsonNode boardNode : boards) {
      String name = boardNode.get("name").asText();
      Board board = reconstruct(boardNode);

      Map<Integer, Integer> expected1 = expectedFor(boardNode, "1");
      Map<Integer, Integer> expected2 = expectedFor(boardNode, "2");
      assertEquals(expected1, acc.countPatterns(board, 1), name + " perspective 1");
      assertEquals(expected2, acc.countPatterns(board, 2), name + " perspective 2");
      if (!expected1.isEmpty() && !expected2.isEmpty()) {
        nonEmptyBoards++;
      }
    }
    assertFalse(nonEmptyBoards == 0, "at least one board must hit real ids on both perspectives");
  }

  @Test
  public void testSignatureFormat() {
    int[] symbols = new int[25];
    symbols[24] = 4; // trailing NORMAL_SELF, rest EMPTY
    PatternContract.Window w = new PatternContract.Window(2, 2, symbols, 7);
    String expected = "0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,4|7";
    assertEquals(expected, NNUEv2Accumulator.signature(w));

    // Non-7 bucket must be serialized verbatim (guards the bucket component,
    // which the promoted dictionary — all bucket 7 — cannot exercise).
    PatternContract.Window near = new PatternContract.Window(2, 2, symbols, 3);
    assertEquals(
        "0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,4|3",
        NNUEv2Accumulator.signature(near));
  }

  @Test
  public void testComputeFullAppliesCountsMultiplicatively() throws Exception {
    PatternDictionary dict = PatternDictionary.load(DICT);
    int K = 3;
    int denseSize = 14;
    // Stub weights: column id == constant so count*weight is easy to reason about.
    float[][] weights = new float[dict.numPatterns()][K];
    for (int id = 0; id < weights.length; id++) {
      for (int i = 0; i < K; i++) {
        weights[id][i] = 1.0f;
      }
    }
    NNUEv2Accumulator acc = new NNUEv2Accumulator(dict, weights, null, K, denseSize);

    // Two identical, isolated, fully-interior pieces so every signature recurs
    // (count == 2) — this is what distinguishes count*weight from a boolean
    // add-once-per-id. Mirrors the "repeated_pattern" parity fixture board.
    Board board = new Board(12, 20);
    board.setCell(5, 5, new Cell(1, CellKind.NORMAL));
    board.setCell(5, 12, new Cell(1, CellKind.NORMAL));

    Map<Integer, Integer> stmCounts = acc.countPatterns(board, 1);
    int totalStm = totalCount(stmCounts);
    int totalNstm = totalCount(acc.countPatterns(board, 2));
    // Guard the premise: at least one id must recur, else boolean vs
    // multiplicative would be indistinguishable and this test would be vacuous.
    assertTrue(
        totalStm > stmCounts.size(), "board must produce a count > 1 to exercise multiplicativity");

    float[] out = acc.computeFull(board, 1, null);
    assertEquals(K * 2 + denseSize, out.length);
    // Every weight column is all-ones, so accum[i] == total occurrence count
    // (sum of counts), which exceeds the distinct-id count under multiplicativity.
    for (int i = 0; i < K; i++) {
      assertEquals((float) totalStm, out[i], "STM accum multiplicative sum");
      assertEquals((float) totalNstm, out[K + i], "NSTM accum multiplicative sum");
    }
  }

  // ---- Task 4: incremental applyMove parity against full recompute ----

  // Single-cell change on an interior empty cell.
  @Test
  public void testParitySingleCellChange() throws Exception {
    Board old = new Board(12, 12);
    old.setCell(2, 2, new Cell(1, CellKind.NORMAL));
    Board neu = copy(old);
    neu.setCell(6, 6, new Cell(1, CellKind.NORMAL));
    assertParityBoth(old, neu);
  }

  // Two changed cells within 4 of each other so their affected-window sets overlap,
  // proving each shared window is decremented/incremented exactly once.
  @Test
  public void testParityMultiCellOverlappingWindows() throws Exception {
    Board old = new Board(12, 12);
    old.setCell(6, 6, new Cell(1, CellKind.NORMAL));
    Board neu = copy(old);
    neu.setCell(6, 6, new Cell(2, CellKind.NORMAL)); // convert
    neu.setCell(6, 8, new Cell(1, CellKind.NORMAL)); // 2 away -> windows overlap
    assertParityBoth(old, neu);
  }

  // Changed cells on the border of a small board so windows include OUT_OF_BOUNDS.
  @Test
  public void testParityEdgeAndOobWindows() throws Exception {
    Board old = new Board(6, 6);
    Board neu = copy(old);
    neu.setCell(0, 1, new Cell(1, CellKind.NORMAL));
    neu.setCell(5, 5, new Cell(2, CellKind.FORTIFIED));
    assertParityBoth(old, neu);
  }

  // Two identical isolated pieces share ids (count 2, no base -> bucket 7 -> real dict hits);
  // removing one must decrement the shared ids by exactly the right amount.
  @Test
  public void testParityRepeatedPatternIds() throws Exception {
    Board old = new Board(12, 20);
    old.setCell(5, 5, new Cell(1, CellKind.NORMAL));
    old.setCell(5, 12, new Cell(1, CellKind.NORMAL));
    Board neu = copy(old);
    neu.setCell(5, 12, new Cell(0, CellKind.EMPTY));
    assertParityBoth(old, neu);
  }

  // Windows near a (static) base carry a non-7 distance bucket, which the all-bucket-7
  // dictionary never contains -> guaranteed misses; parity must hold with misses skipped.
  @Test
  public void testParityDictMissSkipped() throws Exception {
    Board old = new Board(12, 12);
    old.setCell(5, 5, new Cell(2, CellKind.BASE)); // enemy base for owner 1
    old.setCell(5, 7, new Cell(1, CellKind.NORMAL));
    Board neu = copy(old);
    neu.setCell(5, 8, new Cell(1, CellKind.NORMAL)); // base static -> local path, misses skipped
    assertParityBoth(old, neu);
  }

  // Relocating the enemy base shifts every window's bucket for owner 1, tripping the
  // full-recompute fallback; parity must still be exact.
  @Test
  public void testParityBaseMoveFallback() throws Exception {
    Board old = new Board(12, 12);
    old.setCell(3, 3, new Cell(2, CellKind.BASE));
    old.setCell(11, 11, new Cell(1, CellKind.NORMAL)); // far -> bucket 7 -> real hits
    Board neu = copy(old);
    neu.setCell(3, 3, new Cell(0, CellKind.EMPTY));
    neu.setCell(4, 4, new Cell(2, CellKind.BASE)); // enemyBase moves for owner 1 -> fallback
    assertParityBoth(old, neu);
  }

  /** Non-trivial weights/bias so the derived float output is a real (not all-ones) parity check. */
  private static NNUEv2Accumulator buildAccumulator() throws Exception {
    PatternDictionary dict = PatternDictionary.load(DICT);
    int K = 8;
    int denseSize = 14;
    float[][] weights = new float[dict.numPatterns()][K];
    for (int id = 0; id < weights.length; id++) {
      for (int i = 0; i < K; i++) {
        weights[id][i] = (float) ((id * 31 + i) % 7) - 3;
      }
    }
    float[] bias = new float[K];
    for (int i = 0; i < K; i++) {
      bias[i] = i - 2.5f;
    }
    return new NNUEv2Accumulator(dict, weights, bias, K, denseSize);
  }

  private static float[] denseFeatures() {
    float[] d = new float[14];
    for (int i = 0; i < d.length; i++) {
      d[i] = i * 0.5f - 3;
    }
    return d;
  }

  private static Board copy(Board b) {
    Board n = new Board(b.rows, b.cols);
    for (int r = 0; r < b.rows; r++) {
      for (int c = 0; c < b.cols; c++) {
        Cell cell = b.getCell(r, c);
        n.setCell(r, c, new Cell(cell.owner, cell.kind));
      }
    }
    return n;
  }

  private static void assertParityBoth(Board old, Board neu) throws Exception {
    NNUEv2Accumulator acc = buildAccumulator();
    assertParity(acc, old, neu, 1);
    assertParity(acc, old, neu, 2);
  }

  private static void assertParity(NNUEv2Accumulator acc, Board old, Board neu, int activePlayer) {
    float[] dense = denseFeatures();
    NNUEv2Accumulator.State s = acc.newState(old, activePlayer);
    acc.applyMove(s, old, neu, NNUEv2Accumulator.diffCells(old, neu));
    NNUEv2Accumulator.State full = acc.newState(neu, activePlayer);
    String tag = "activePlayer=" + activePlayer;
    assertEquals(full.stmCounts(), s.stmCounts(), "stm counts " + tag);
    assertEquals(full.nstmCounts(), s.nstmCounts(), "nstm counts " + tag);
    assertArrayEquals(
        acc.computeFull(neu, activePlayer, dense), acc.output(s, dense), 0.0f, "output " + tag);
  }

  private static int totalCount(Map<Integer, Integer> counts) {
    int total = 0;
    for (int c : counts.values()) {
      total += c;
    }
    return total;
  }

  private static Board reconstruct(JsonNode boardNode) {
    Board board = new Board(boardNode.get("rows").asInt(), boardNode.get("cols").asInt());
    for (JsonNode cell : boardNode.get("cells")) {
      board.setCell(
          cell.get("r").asInt(),
          cell.get("c").asInt(),
          new Cell(cell.get("owner").asInt(), CellKind.valueOf(cell.get("kind").asText())));
    }
    return board;
  }

  private static Map<Integer, Integer> expectedFor(JsonNode boardNode, String perspective) {
    Map<Integer, Integer> map = new HashMap<>();
    JsonNode expected = boardNode.get("expected").get(perspective);
    for (Iterator<Map.Entry<String, JsonNode>> it = expected.fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> e = it.next();
      map.put(Integer.parseInt(e.getKey()), e.getValue().asInt());
    }
    return map;
  }
}
