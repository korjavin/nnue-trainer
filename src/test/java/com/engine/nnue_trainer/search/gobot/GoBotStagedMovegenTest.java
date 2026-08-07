package com.engine.nnue_trainer.search.gobot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Stage-1 self-check for plan item 1 (staged move generation). Byte-exact move/score equality with
 * the unstaged search is already pinned by {@code GoBotSearchParityTest} /
 * {@code GoBotNodeBudgetParityTest}; this test asserts the fast path actually FIRES — i.e. that
 * iterative deepening produces TT-move-first cutoffs that skip sibling materialization — so the
 * speedup cannot silently regress to dead code.
 */
public class GoBotStagedMovegenTest {

  @Test
  public void fastPathCutsOccurDuringIterativeDeepening() throws Exception {
    List<GoState> states = fixtureStates(5);
    assertTrue(states.size() >= 3, "fixture yielded too few positions");
    long totalCuts = 0;
    for (GoState state : states) {
      GoBotSearcher s = GoBotSearcher.newSearcher(state);
      for (int depth = 1; depth <= 4; depth++) {
        GoResult r = s.atDepth(state, depth);
        assertNotNull(r);
      }
      totalCuts += s.fastPathCuts;
    }
    assertTrue(
        totalCuts > 0,
        "TT-move-first fast path never cut across " + states.size() + " ID runs to depth 4");
  }

  /** Nodes/evaluations must be untouched by staging: pin them against an ID rerun. */
  @Test
  public void countersAreDeterministicAcrossRuns() throws Exception {
    List<GoState> states = fixtureStates(3);
    for (GoState state : states) {
      GoBotSearcher a = GoBotSearcher.newSearcher(state);
      GoBotSearcher b = GoBotSearcher.newSearcher(state);
      for (int depth = 1; depth <= 4; depth++) {
        a.atDepth(state, depth);
        b.atDepth(state, depth);
      }
      assertEquals(a.nodes, b.nodes, "node counts must be deterministic");
      assertEquals(a.evaluations, b.evaluations, "evaluation counts must be deterministic");
    }
  }

  /** First {@code max} non-opening-book positions from the node-budget parity fixture. */
  static List<GoState> fixtureStates(int max) throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    List<GoState> states = new ArrayList<>();
    try (InputStream is =
        GoBotStagedMovegenTest.class.getResourceAsStream("/gobot_nodebudget_parity.jsonl")) {
      assertNotNull(is, "missing /gobot_nodebudget_parity.jsonl");
      BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
      String line;
      while ((line = reader.readLine()) != null && states.size() < max) {
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
        GoState state =
            GoState.fromBoard(
                toBoard(rec.get("board")),
                rec.get("player").asInt(),
                rec.get("movesLeft").asInt(),
                neutralUsed);
        if (GoOpeningBook.openingBookResult(state) != null) {
          continue; // book positions never reach the search core
        }
        states.add(state);
      }
    }
    return states;
  }

  private static Board toBoard(JsonNode boardNode) {
    int rows = boardNode.size();
    int cols = boardNode.get(0).size();
    Board board = new Board(rows, cols);
    for (int r = 0; r < rows; r++) {
      for (int c = 0; c < cols; c++) {
        JsonNode cellNode = boardNode.get(r).get(c);
        board.setCell(
            r,
            c,
            new Cell(cellNode.get("owner").asInt(), CellKind.valueOf(cellNode.get("kind").asText())));
      }
    }
    return board;
  }
}
