package com.engine.nnue_trainer.search.gobot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.engine.nnue_trainer.board.Action;
import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.board.MoveAction;
import com.engine.nnue_trainer.search.ordering.PolicyOrderingTable;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Stage-1 neural ordering integration (epic 1jh): the {@link PolicyOrderingTable} score must
 * actually re-rank quiet {@code MoveAction}s on the enhanced path, and a null table must keep the
 * pre-table ordering (board order for tied quiets) byte-identical.
 */
public class GoBotOrderingTableTest {

  /** An early-game 12x12 position with several quiet moves and no captures/wins/eliminations. */
  private static GoState earlyState() {
    Board board = new Board(12, 12);
    board.setCell(0, 0, new Cell(1, CellKind.BASE));
    board.setCell(11, 11, new Cell(2, CellKind.BASE));
    board.setCell(1, 1, new Cell(1, CellKind.NORMAL));
    board.setCell(2, 2, new Cell(1, CellKind.NORMAL));
    // movesLeft=2 keeps neutral pairs out of the action set (they only appear at turn start).
    return GoState.fromBoard(board, 1, 2, new boolean[] {false, false});
  }

  @Test
  public void tableScoreRanksQuietMoves() throws Exception {
    assumeTrue(
        Files.isRegularFile(PolicyOrderingTable.DEFAULT_WEIGHTS),
        "ordering_policy.json artifact not present");
    PolicyOrderingTable table = PolicyOrderingTable.load(PolicyOrderingTable.DEFAULT_WEIGHTS);
    GoState state = earlyState();
    double[] scores = table.score(state.toBoard(), 1, state.movesLeft());

    PolicyOrderingTable prev = GoBotSearcher.setOrderingTable(table);
    try {
      GoBotSearcher enhanced = GoBotSearcher.newSearcher(state, true);
      List<Action> withTable = enhanced.orderedActions(state, null, false, 0);
      List<Action> boardOrder =
          GoBotSearcher.newSearcher(state, false).orderedActions(state, null, false, 0);

      // Same legal action set either way.
      assertEquals(new HashSet<>(boardOrder), new HashSet<>(withTable));
      // The table genuinely reorders: this position's table ranking differs from board order.
      assertNotEquals(boardOrder, withTable);
      // Contract: quiet MoveActions ranked by scores[r*12+c] descending. All moves in this
      // position share the same static tier, so the whole sequence must match the table sort.
      List<Action> expected = new ArrayList<>(boardOrder);
      expected.sort(
          (a, b) -> {
            double sa = scores[((MoveAction) a).target.row * 12 + ((MoveAction) a).target.col];
            double sb = scores[((MoveAction) b).target.row * 12 + ((MoveAction) b).target.col];
            return Double.compare(sb, sa);
          });
      assertEquals(expected, withTable);
    } finally {
      GoBotSearcher.setOrderingTable(prev);
    }
  }

  @Test
  public void nullTableKeepsCurrentBehavior() {
    GoState state = earlyState();
    PolicyOrderingTable prev = GoBotSearcher.setOrderingTable(null);
    try {
      List<Action> enhanced =
          GoBotSearcher.newSearcher(state, true).orderedActions(state, null, false, 0);
      List<Action> parity =
          GoBotSearcher.newSearcher(state, false).orderedActions(state, null, false, 0);
      assertEquals(parity, enhanced, "null table must keep the pre-table ordering exactly");
    } finally {
      GoBotSearcher.setOrderingTable(prev);
    }
  }

  /**
   * Ordering self-check (env-gated: {@code GOBOT_ORDERING_CHECK=1}): nodes-to-depth and mean
   * fail-high index over parity-fixture positions — the stage-1 A/B evidence lived here; the
   * winning variant's numbers are documented at the sort in {@code GoBotSearcher.orderedChildren}.
   */
  @Test
  public void orderingSelfCheck() throws Exception {
    assumeTrue("1".equals(System.getenv("GOBOT_ORDERING_CHECK")), "set GOBOT_ORDERING_CHECK=1");
    assumeTrue(Files.isRegularFile(PolicyOrderingTable.DEFAULT_WEIGHTS));
    List<GoState> states = fixtureStates(Integer.getInteger("gobot.ordering.check.positions", 50));
    int depth = Integer.getInteger("gobot.ordering.check.depth", 4);
    long nodes = 0;
    long cuts = 0;
    long cutIndexSum = 0;
    long t0 = System.currentTimeMillis();
    for (GoState state : states) {
      GoBotSearcher s = GoBotSearcher.newSearcher(state, true);
      s.searchToDepth(state, depth);
      nodes += s.nodes;
      cuts += s.cutCount;
      cutIndexSum += s.cutIndexSum;
    }
    System.out.printf(
        "depth=%d positions=%d nodes=%d cuts=%d meanFailHighIndex=%.4f wallMs=%d%n",
        depth,
        states.size(),
        nodes,
        cuts,
        cuts == 0 ? 0.0 : (double) cutIndexSum / cuts,
        System.currentTimeMillis() - t0);
    assertTrue(cuts > 0);
  }

  static List<GoState> fixtureStates(int limit) throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    List<GoState> states = new ArrayList<>();
    try (InputStream is =
        GoBotOrderingTableTest.class.getResourceAsStream("/gobot_search_parity.jsonl")) {
      BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
      String line;
      while ((line = reader.readLine()) != null && states.size() < limit) {
        line = line.trim();
        if (line.isEmpty()) {
          continue;
        }
        JsonNode rec = mapper.readTree(line);
        JsonNode neutralNode = rec.get("neutralUsed");
        boolean[] neutralUsed = new boolean[neutralNode.size()];
        for (int i = 0; i < neutralNode.size(); i++) {
          neutralUsed[i] = neutralNode.get(i).asBoolean();
        }
        JsonNode boardNode = rec.get("board");
        int rows = boardNode.size();
        int cols = boardNode.get(0).size();
        Board board = new Board(rows, cols);
        for (int r = 0; r < rows; r++) {
          for (int c = 0; c < cols; c++) {
            JsonNode cell = boardNode.get(r).get(c);
            board.setCell(
                r,
                c,
                new Cell(cell.get("owner").asInt(), CellKind.valueOf(cell.get("kind").asText())));
          }
        }
        states.add(
            GoState.fromBoard(
                board, rec.get("player").asInt(), rec.get("movesLeft").asInt(), neutralUsed));
      }
    }
    return states;
  }
}
