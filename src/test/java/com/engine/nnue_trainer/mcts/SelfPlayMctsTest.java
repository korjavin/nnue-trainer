package com.engine.nnue_trainer.mcts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.search.gobot.GoState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Phase 2 self-play record contract: row shape, the absolute-frame z (the critical frame-sign
 * pin — the v3 mover-flip lesson), and byte-level determinism per (seed, shard). */
class SelfPlayMctsTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final int FLAT = 144 + 144 * 144;

  private static List<JsonNode> playRows(GoState start, long seed, int sims) throws Exception {
    StringWriter sw = new StringWriter();
    MctsSearcher.Config cfg = new MctsSearcher.Config();
    cfg.rootNoise = true;
    try (BufferedWriter w = new BufferedWriter(sw)) {
      if (start == null) {
        SelfPlayMcts.playGame("g", seed, sims, cfg, w);
      } else {
        SelfPlayMcts.playGame("g", seed, sims, cfg, w, start);
      }
    }
    List<JsonNode> rows = new ArrayList<>();
    for (String line : sw.toString().split("\n")) {
      if (!line.isBlank()) {
        rows.add(MAPPER.readTree(line));
      }
    }
    return rows;
  }

  @Test
  void rowsHaveTheTrainingSchema() throws Exception {
    List<JsonNode> rows = playRows(null, 7L, 12);
    assertTrue(rows.size() > 20, "a full self-play game records many positions");
    Set<Integer> movers = new HashSet<>();
    int z0 = rows.get(0).get("z").asInt();
    for (JsonNode r : rows) {
      assertEquals("g", r.get("g").asText());
      assertEquals(144, r.get("sym").size());
      for (JsonNode s : r.get("sym")) {
        assertTrue(s.asInt() >= 0 && s.asInt() < 8, "sym in the 8-state encoding");
      }
      int ml = r.get("ml").asInt();
      assertTrue(ml >= 1 && ml <= 3);
      assertTrue(r.get("nuo").asInt() == 0 || r.get("nuo").asInt() == 1);
      assertTrue(r.get("nux").asInt() == 0 || r.get("nux").asInt() == 1);
      int mover = r.get("mover").asInt();
      assertTrue(mover == 1 || mover == 2);
      movers.add(mover);

      JsonNode pi = r.get("pi");
      JsonNode pv = r.get("pv");
      assertEquals(pi.size(), pv.size(), "pi/pv aligned");
      assertTrue(pi.size() > 1, "forced positions are not recorded");
      Set<Integer> seen = new HashSet<>();
      long visits = 0;
      for (int a = 0; a < pi.size(); a++) {
        int id = pi.get(a).asInt();
        assertTrue(id >= 0 && id < FLAT, "flat action id in range");
        assertTrue(seen.add(id), "legal action ids are unique");
        assertTrue(pv.get(a).asInt() >= 0);
        visits += pv.get(a).asInt();
      }
      assertEquals(12, visits, "root visit counts sum to the sim budget");

      int z = r.get("z").asInt();
      assertTrue(z >= -1 && z <= 1);
      assertEquals(z0, z, "every row of a game shares the game outcome");
    }
    assertTrue(movers.contains(1) && movers.contains(2), "both movers recorded");
  }

  /**
   * THE frame-sign test: a start where P1 is walled in and P2 (to move) wins immediately. The
   * recorded rows have mover == 2, and z must be the ABSOLUTE outcome -1 (P2 won), not the
   * mover-frame +1 — exactly the sign the v3 emitter once got wrong.
   */
  @Test
  void zIsRecordedInTheAbsoluteFrameNotTheMoverFrame() throws Exception {
    Board board = new Board(12, 12);
    board.setCell(0, 0, new Cell(1, CellKind.BASE));
    board.setCell(11, 11, new Cell(2, CellKind.BASE));
    board.setCell(0, 1, new Cell(2, CellKind.FORTIFIED));
    board.setCell(1, 0, new Cell(2, CellKind.FORTIFIED));
    board.setCell(1, 1, new Cell(2, CellKind.FORTIFIED));
    board.setCell(10, 10, new Cell(2, CellKind.NORMAL));
    GoState start = GoState.fromBoard(board, 2, 3, new boolean[2]);
    assertFalse(start.gameOver(), "precondition: game still open before P2 moves");

    List<JsonNode> rows = playRows(start, 3L, 8);
    assertTrue(rows.size() >= 1, "the winning P2 position is recorded");
    for (JsonNode r : rows) {
      assertEquals(2, r.get("mover").asInt(), "P2 is the mover in every recorded row");
      assertEquals(-1, r.get("z").asInt(), "z is absolute: P2's win is -1 even on P2's rows");
    }
  }

  @Test
  void deterministicPerSeedAndDivergentAcrossSeeds() throws Exception {
    String a = String.valueOf(playRows(null, 42L, 8));
    String b = String.valueOf(playRows(null, 42L, 8));
    String c = String.valueOf(playRows(null, 43L, 8));
    assertEquals(a, b, "same (seed) => byte-identical rows");
    assertFalse(a.equals(c), "different seed => different game");
  }

  @Test
  void flatIndexMatchesTheTrainerActionSpace() {
    assertEquals(
        5 * 12 + 7,
        SelfPlayMcts.flatIndex(
            new com.engine.nnue_trainer.board.MoveAction(new com.engine.nnue_trainer.board.Pos(5, 7))));
    // Pair id is order-independent and uses i<j: 144 + min*144 + max.
    com.engine.nnue_trainer.board.Pos p1 = new com.engine.nnue_trainer.board.Pos(0, 3);
    com.engine.nnue_trainer.board.Pos p2 = new com.engine.nnue_trainer.board.Pos(2, 1);
    int expected = 144 + 3 * 144 + 25;
    assertEquals(
        expected, SelfPlayMcts.flatIndex(new com.engine.nnue_trainer.board.PlaceNeutralsAction(p1, p2)));
    assertEquals(
        expected, SelfPlayMcts.flatIndex(new com.engine.nnue_trainer.board.PlaceNeutralsAction(p2, p1)));
  }
}
