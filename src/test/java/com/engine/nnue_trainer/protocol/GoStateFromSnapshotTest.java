package com.engine.nnue_trainer.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.search.gobot.GoState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Task 3: the live GoState inputs match the oracle. Each parity-fixture record is re-serialized
 * into the <b>real server wire format</b> — {@code game.Cell} has no JSON tags, so the backend
 * emits capitalized {@code "Owner"}/{@code "Kind"} keys with {@code Kind} as an <i>integer</i> (Go
 * iota: Empty=0..Neutral=4), unlike the fixture's lowercase {@code "owner"} / string {@code
 * "kind"}. We feed that wire snapshot through {@link GameLoopHandler#goStateFromSnapshot} (the live
 * construction point) and assert the resulting {@link GoState} is byte-for-byte identical (FNV
 * {@code hash()} + every field) to the oracle's {@code GoState.fromBoard} for the same logical
 * position. A pass means board orientation, {@code CellKind} mapping, current player, movesLeft,
 * and per-player neutralUsed survive the live parse untouched, so any remaining divergence is the
 * action→move translation (Task 4), not the inputs.
 */
public class GoStateFromSnapshotTest {

  @Test
  public void liveSnapshotBuildsOracleGoState() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    int records = 0;

    try (InputStream is = getClass().getResourceAsStream("/gobot_nodebudget_parity.jsonl")) {
      assertTrue(is != null, "Could not find /gobot_nodebudget_parity.jsonl");
      BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
      String line;
      while ((line = reader.readLine()) != null) {
        line = line.trim();
        if (line.isEmpty()) {
          continue;
        }
        JsonNode rec = mapper.readTree(line);
        int player = rec.get("player").asInt();
        int movesLeft = rec.get("movesLeft").asInt();
        JsonNode neutralNode = rec.get("neutralUsed");
        boolean[] neutralUsed = new boolean[neutralNode.size()];
        for (int i = 0; i < neutralNode.size(); i++) {
          neutralUsed[i] = neutralNode.get(i).asBoolean();
        }

        // Oracle GoState: fixture board (lowercase owner / string kind) fed straight to fromBoard.
        Board fixtureBoard = toBoard(rec.get("board"));
        GoState oracle = GoState.fromBoard(fixtureBoard, player, movesLeft, neutralUsed);

        // Live GoState: the same position re-encoded as the backend actually sends it, then parsed
        // by the exact live construction path.
        JsonNode wireSnapshot = toWireSnapshot(mapper, rec, player, movesLeft, neutralNode);
        GoState live = GameLoopHandler.goStateFromSnapshot(wireSnapshot);

        records++;
        String where = " (record " + records + ", player=" + player + ")";
        assertEquals(oracle.hash(), live.hash(), "GoState hash mismatch" + where);
        assertEquals(oracle.rows(), live.rows(), "rows mismatch" + where);
        assertEquals(oracle.cols(), live.cols(), "cols mismatch" + where);
        assertEquals(
            oracle.currentPlayer(), live.currentPlayer(), "currentPlayer mismatch" + where);
        assertEquals(oracle.movesLeft(), live.movesLeft(), "movesLeft mismatch" + where);
        for (int p = 1; p <= 2; p++) {
          assertEquals(
              oracle.neutralUsed(p),
              live.neutralUsed(p),
              "neutralUsed[" + p + "] mismatch" + where);
        }
      }
    }

    assertTrue(records > 0, "fixture was empty");
  }

  /**
   * Re-encode a fixture record as the backend's real wire snapshot (capital keys, integer Kind).
   */
  private static JsonNode toWireSnapshot(
      ObjectMapper mapper, JsonNode rec, int player, int movesLeft, JsonNode neutralNode) {
    JsonNode fixtureBoard = rec.get("board");
    int rows = fixtureBoard.size();
    int cols = fixtureBoard.get(0).size();

    ObjectNode snapshot = mapper.createObjectNode();
    snapshot.put("rows", rows);
    snapshot.put("cols", cols);
    snapshot.put("currentPlayer", player);
    snapshot.put("movesLeft", movesLeft);
    snapshot.put("gameOver", false);

    ArrayNode board = mapper.createArrayNode();
    for (int r = 0; r < rows; r++) {
      JsonNode rowNode = fixtureBoard.get(r);
      ArrayNode wireRow = mapper.createArrayNode();
      for (int c = 0; c < cols; c++) {
        JsonNode cellNode = rowNode.get(c);
        ObjectNode wireCell = mapper.createObjectNode();
        wireCell.put("Owner", cellNode.get("owner").asInt());
        wireCell.put("Kind", CellKind.valueOf(cellNode.get("kind").asText()).value);
        wireRow.add(wireCell);
      }
      board.add(wireRow);
    }
    snapshot.set("board", board);
    snapshot.set("neutralUsed", neutralNode.deepCopy());
    return snapshot;
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
