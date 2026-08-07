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
 * Contract test for bd nnue-trainer-0dj.7: the wall-clock live entry point {@link
 * GoBotSearcher#chooseWithDeadline} must return exactly the move the deterministic parity oracle
 * {@link GoBotSearcher#chooseDepth} returns at the deepest FULLY COMPLETED iteration — a partially
 * searched (deadline-aborted) iteration must never leak into the returned move. Positions come from
 * the same mid-game fixture the node-budget parity oracle uses; opening-book positions are skipped
 * because the book answers before iterative deepening runs.
 *
 * <p>The deadline is wall-clock, so which depth completes varies run to run — but the contract must
 * hold at WHATEVER depth the search reports, so the assertion is timing-independent.
 */
public class GoBotChooseDeadlineConsistencyTest {

  /**
   * choose(deadline) with a workable budget: the reported depth must be a completed iteration and
   * the move must equal chooseDepth at that depth.
   */
  @Test
  public void chooseMatchesChooseDepthAtReportedDepth() throws Exception {
    List<GoState> states = fixtureStates(4);
    assertTrue(states.size() >= 2, "fixture yielded too few non-book positions");
    for (GoState state : states) {
      GoResult live = GoBotSearcher.chooseWithDeadline(state, System.currentTimeMillis() + 800);
      assertNotNull(live);
      assertNotNull(live.action, "choose returned no action");
      assertTrue(live.depth >= 1, "800ms budget should complete at least depth 1");
      GoResult oracle = GoBotSearcher.chooseDepth(state, live.depth);
      assertNotNull(oracle);
      assertEquals(
          oracle.action,
          live.action,
          "choose(deadline) diverged from chooseDepth at its own reported depth " + live.depth);
      assertEquals(oracle.score, live.score, "score diverged at reported depth " + live.depth);
    }
  }

  /**
   * Early-deadline case: with a tiny (even already-expired) deadline the result must be the
   * fallback (reported depth 0) or exactly chooseDepth at whatever shallow depth completed — never
   * a half-searched deeper move.
   */
  @Test
  public void earlyDeadlineNeverLeaksPartialIteration() throws Exception {
    List<GoState> states = fixtureStates(3);
    assertTrue(states.size() >= 2, "fixture yielded too few non-book positions");
    long[] budgets = {0, 1, 2, 5};
    for (GoState state : states) {
      for (long budget : budgets) {
        GoResult live = GoBotSearcher.chooseWithDeadline(state, System.currentTimeMillis() + budget);
        assertNotNull(live);
        assertNotNull(live.action, "choose returned no action at budget " + budget);
        if (live.depth == 0) {
          // Fallback path: no iteration completed. The action must at least be legal.
          assertTrue(
              state.legalActions().contains(live.action),
              "fallback action is not legal at budget " + budget);
        } else {
          GoResult oracle = GoBotSearcher.chooseDepth(state, live.depth);
          assertNotNull(oracle);
          assertEquals(
              oracle.action,
              live.action,
              "budget " + budget + "ms leaked a move differing from chooseDepth " + live.depth);
        }
      }
    }
  }

  /** First {@code max} non-opening-book positions from the node-budget parity fixture. */
  private static List<GoState> fixtureStates(int max) throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    List<GoState> states = new ArrayList<>();
    try (InputStream is =
        GoBotChooseDeadlineConsistencyTest.class.getResourceAsStream(
            "/gobot_nodebudget_parity.jsonl")) {
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
          continue; // book answers before iterative deepening; not the contract under test
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
