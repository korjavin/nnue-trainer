package com.engine.nnue_trainer.search.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Integer-exact parity: the Java port of GoBot's hand-tuned static eval must return the same
 * integer score as GoBot for every record in the reference fixture. The fixture is generated from
 * GoBot's own {@code StaticEval}, so any diff is a port bug (a hand-tuned eval has no randomness).
 */
public class HandTunedEvalParityTest {

  @Test
  public void integerExactParityOnFixture() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    int records = 0;
    int mismatches = 0;
    StringBuilder firstFew = new StringBuilder();

    try (InputStream is = getClass().getResourceAsStream("/gobot_staticeval_parity.jsonl")) {
      if (is == null) {
        throw new RuntimeException("Could not find /gobot_staticeval_parity.jsonl");
      }
      BufferedReader reader =
          new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
      String line;
      while ((line = reader.readLine()) != null) {
        line = line.trim();
        if (line.isEmpty()) {
          continue;
        }
        JsonNode rec = mapper.readTree(line);
        JsonNode boardNode = rec.get("board");
        int player = rec.get("player").asInt();
        int expected = rec.get("score").asInt();
        int movesLeft = rec.get("movesLeft").asInt();
        JsonNode neutralNode = rec.get("neutralUsed");
        boolean[] neutralUsed = new boolean[neutralNode.size()];
        for (int i = 0; i < neutralNode.size(); i++) {
          neutralUsed[i] = neutralNode.get(i).asBoolean();
        }

        Board board = toBoard(boardNode);
        int actual = HandTunedEval.staticEval(board, player, movesLeft, neutralUsed);
        records++;
        if (actual != expected) {
          mismatches++;
          if (mismatches <= 10) {
            firstFew
                .append("\n  record ")
                .append(records)
                .append(" player=")
                .append(player)
                .append(" movesLeft=")
                .append(movesLeft)
                .append(" expected=")
                .append(expected)
                .append(" actual=")
                .append(actual);
          }
        }
      }
    }

    assertTrue(records > 0, "fixture was empty");
    assertEquals(
        0,
        mismatches,
        mismatches + "/" + records + " positions mismatched:" + firstFew);
  }

  private static Board toBoard(JsonNode boardNode) {
    int rows = boardNode.size();
    int cols = boardNode.get(0).size();
    Board board = new Board(rows, cols);
    for (int r = 0; r < rows; r++) {
      JsonNode rowNode = boardNode.get(r);
      for (int c = 0; c < cols; c++) {
        JsonNode cellNode = rowNode.get(c);
        int owner = cellNode.get("owner").asInt();
        CellKind kind = CellKind.valueOf(cellNode.get("kind").asText());
        board.setCell(r, c, new Cell(owner, kind));
      }
    }
    return board;
  }
}
