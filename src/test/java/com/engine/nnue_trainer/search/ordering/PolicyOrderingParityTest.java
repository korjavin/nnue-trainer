package com.engine.nnue_trainer.search.ordering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link PolicyOrderingTable#score} against cell scores computed by {@code train_ordering.py}
 * from the same weights file — the {@code PolicyNetPriorParityTest} discipline. A drift between the
 * python feature ids and the Java accumulator must fail here, not as a silently-shuffled move order
 * in a gauntlet.
 */
class PolicyOrderingParityTest {

  private static final Path WEIGHTS = Path.of("ordering_policy.json");
  private static final Path FIXTURE =
      Path.of("src", "test", "resources", "ordering", "ordering_parity.json");

  /** Both sides sum the same 145 rounded doubles; only summation order differs. */
  private static final double TOL = 1e-9;

  /** Inverse of PatternContract.getSymbol for stm=1: symbol -> (owner, kind). */
  private static Board boardFromSymbols(JsonNode sym) {
    Board b = new Board(12, 12);
    for (int i = 0; i < 144; i++) {
      int s = sym.get(i).asInt();
      int owner = s < 2 ? 0 : (s % 2 == 0 ? 1 : 2);
      CellKind kind =
          switch (s) {
            case 0 -> CellKind.EMPTY;
            case 1 -> CellKind.NEUTRAL;
            case 2, 3 -> CellKind.BASE;
            case 4, 5 -> CellKind.NORMAL;
            default -> CellKind.FORTIFIED;
          };
      b.setCell(i / 12, i % 12, new Cell(owner, kind));
    }
    return b;
  }

  @Test
  void scoresMatchPythonFixture() throws Exception {
    assertTrue(Files.exists(WEIGHTS), "trained weights artifact missing: " + WEIGHTS);
    assertTrue(Files.exists(FIXTURE), "parity fixture missing: " + FIXTURE);
    PolicyOrderingTable table = PolicyOrderingTable.load(WEIGHTS);
    JsonNode fixture = new ObjectMapper().readTree(Files.readAllBytes(FIXTURE));
    JsonNode samples = fixture.get("samples");
    assertTrue(samples.isArray() && samples.size() > 0, "fixture has samples");

    for (JsonNode sample : samples) {
      Board board = boardFromSymbols(sample.get("sym"));
      double[] scores = table.score(board, 1, sample.get("ml").asInt());
      for (int c = 0; c < 144; c++) {
        assertEquals(sample.get("scores").get(c).doubleValue(), scores[c], TOL, "cell " + c);
      }
    }
  }

  /** Identical scores everywhere would pass any index mapping — the fixture must discriminate. */
  @Test
  void fixtureScoresAreNotAllEqual() throws Exception {
    JsonNode fixture = new ObjectMapper().readTree(Files.readAllBytes(FIXTURE));
    JsonNode scores = fixture.get("samples").get(0).get("scores");
    double first = scores.get(0).doubleValue();
    boolean varies = false;
    for (JsonNode v : scores) {
      varies |= Math.abs(v.doubleValue() - first) > 1e-6;
    }
    assertTrue(varies, "parity fixture scores are all identical — it proves nothing");
  }
}
