package com.engine.nnue_trainer.v2;

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
