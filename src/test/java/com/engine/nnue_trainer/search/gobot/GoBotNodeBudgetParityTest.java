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
 * Deterministic live-path oracle (Task 2). {@link GoBotSearcher#chooseNodeBudget} exercises the full
 * iterative-deepening + opening-book + root-move-selection path that live play uses via {@code
 * choose} — but bounded by a node limit instead of wall-clock, so it is reproducible. It must pick
 * the <b>same action</b> and return the <b>same integer score</b> as GoBot's {@code
 * ChooseNodeBudget} for every record in the fixture. A pass means the search side is faithful and
 * any live divergence is wiring (Tasks 3–4); a fail localizes the bug to the iterative-deepening
 * port. See {@code gobot_search_parity.README.md} for the schema.
 */
public class GoBotNodeBudgetParityTest {

  @Test
  public void nodeBudgetMoveParityOnFixture() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    int records = 0;
    int mismatches = 0;
    StringBuilder firstFew = new StringBuilder();

    try (InputStream is = getClass().getResourceAsStream("/gobot_nodebudget_parity.jsonl")) {
      if (is == null) {
        throw new RuntimeException("Could not find /gobot_nodebudget_parity.jsonl");
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
        long nodeLimit = rec.get("nodeLimit").asLong();
        int expectedScore = rec.get("score").asInt();
        int movesLeft = rec.get("movesLeft").asInt();
        JsonNode neutralNode = rec.get("neutralUsed");
        boolean[] neutralUsed = new boolean[neutralNode.size()];
        for (int i = 0; i < neutralNode.size(); i++) {
          neutralUsed[i] = neutralNode.get(i).asBoolean();
        }
        Action expectedAction = toAction(rec.get("action"));
        Board board = toBoard(rec.get("board"));

        GoState state = GoState.fromBoard(board, player, movesLeft, neutralUsed);
        GoResult result = GoBotSearcher.chooseNodeBudget(state, nodeLimit);
        records++;

        boolean actionMatch = result != null && expectedAction.equals(result.action);
        boolean scoreMatch = result != null && expectedScore == result.score;
        if (!actionMatch || !scoreMatch) {
          mismatches++;
          if (mismatches <= 10) {
            firstFew
                .append("\n  record ")
                .append(records)
                .append(" player=")
                .append(player)
                .append(" nodeLimit=")
                .append(nodeLimit)
                .append(" movesLeft=")
                .append(movesLeft)
                .append(" expectedAction=")
                .append(expectedAction)
                .append(" actualAction=")
                .append(result == null ? "null" : result.action)
                .append(" expectedScore=")
                .append(expectedScore)
                .append(" actualScore=")
                .append(result == null ? "null" : result.score);
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
