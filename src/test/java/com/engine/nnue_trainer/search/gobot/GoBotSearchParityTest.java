package com.engine.nnue_trainer.search.gobot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.engine.nnue_trainer.board.Action;
import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.board.MoveAction;
import com.engine.nnue_trainer.board.PlaceNeutralsAction;
import com.engine.nnue_trainer.board.Pos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The acceptance gate for the GoBot search port (Task 5). A fully-completed fixed-depth search is
 * deterministic, so the ported {@link GoBotSearcher#chooseDepth} must pick the <b>same action</b>
 * and return the <b>same integer score</b> as GoBot's {@code search.ChooseDepth} for every record
 * in the reference fixture. Any diff is a port bug (ordering / PVS / TT / hidden-state). See {@code
 * gobot_search_parity.README.md} for the schema and hidden-state contract.
 */
public class GoBotSearchParityTest {

  @Test
  public void fixedDepthMoveParityOnFixture() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    int records = 0;
    int mismatches = 0;
    StringBuilder firstFew = new StringBuilder();

    try (InputStream is = getClass().getResourceAsStream("/gobot_search_parity.jsonl")) {
      if (is == null) {
        throw new RuntimeException("Could not find /gobot_search_parity.jsonl");
      }
      BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
      String line;
      while ((line = reader.readLine()) != null) {
        line = line.trim();
        if (line.isEmpty()) {
          continue;
        }
        JsonNode rec = mapper.readTree(line);
        int player = rec.get("player").asInt();
        int depth = rec.get("depth").asInt();
        int expectedScore = rec.get("score").asInt();
        int movesLeft = rec.get("movesLeft").asInt();
        JsonNode neutralNode = rec.get("neutralUsed");
        boolean[] neutralUsed = new boolean[neutralNode.size()];
        for (int i = 0; i < neutralNode.size(); i++) {
          neutralUsed[i] = neutralNode.get(i).asBoolean();
        }
        Action expectedAction = toAction(rec.get("action"));
        Board board = toBoard(rec.get("board"));

        GoResult result = GoBotSearcher.chooseDepth(board, player, movesLeft, neutralUsed, depth);
        records++;

        boolean actionMatch = expectedAction.equals(result.action);
        boolean scoreMatch = expectedScore == result.score;
        if (!actionMatch || !scoreMatch) {
          mismatches++;
          if (mismatches <= 10) {
            firstFew
                .append("\n  record ")
                .append(records)
                .append(" player=")
                .append(player)
                .append(" depth=")
                .append(depth)
                .append(" movesLeft=")
                .append(movesLeft)
                .append(" expectedAction=")
                .append(expectedAction)
                .append(" actualAction=")
                .append(result.action)
                .append(" expectedScore=")
                .append(expectedScore)
                .append(" actualScore=")
                .append(result.score);
          }
        }
      }
    }

    assertTrue(records > 0, "fixture was empty");
    assertEquals(0, mismatches, mismatches + "/" + records + " positions mismatched:" + firstFew);
  }

  private static Action toAction(JsonNode actionNode) {
    String type = actionNode.get("type").asText();
    if ("MOVE".equals(type)) {
      JsonNode t = actionNode.get("target");
      return new MoveAction(new Pos(t.get("row").asInt(), t.get("col").asInt()));
    } else if ("PLACE_NEUTRALS".equals(type)) {
      JsonNode p1 = actionNode.get("pos1");
      JsonNode p2 = actionNode.get("pos2");
      return new PlaceNeutralsAction(
          new Pos(p1.get("row").asInt(), p1.get("col").asInt()),
          new Pos(p2.get("row").asInt(), p2.get("col").asInt()));
    }
    throw new IllegalArgumentException("Unknown action type: " + type);
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
