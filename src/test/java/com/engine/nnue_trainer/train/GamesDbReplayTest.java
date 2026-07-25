package com.engine.nnue_trainer.train;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.engine.nnue_trainer.board.CellKind;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** Behaviour of the shared games.db replay extracted from GamesDbPatternMiner. */
public class GamesDbReplayTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static JsonNode turns(String json) {
    try {
      return MAPPER.readTree(json);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  public void testTwoPlayerReplayProducesSnapshotPerTurn() {
    // p1 grows from its base at (0,0); p2 grows from its base at (7,7).
    JsonNode t =
        turns(
            "["
                + "{\"turn\":1,\"player\":1,\"moves\":[{\"type\":\"place\",\"row\":0,\"col\":1}]},"
                + "{\"turn\":2,\"player\":2,\"moves\":[{\"type\":\"place\",\"row\":7,\"col\":6}]},"
                + "{\"turn\":3,\"player\":1,\"moves\":[{\"type\":\"place\",\"row\":1,\"col\":1}]}"
                + "]");

    GamesDbReplay.Replay r = GamesDbReplay.replay(8, 8, t);

    assertNull(r.skipReason);
    assertEquals(3, r.snapshots.size());
    assertEquals(1, r.snapshots.get(0).stm);
    assertEquals(2, r.snapshots.get(1).stm);
    assertEquals(1, r.snapshots.get(2).stm);

    // First snapshot is the untouched initial board (bases only).
    assertEquals(CellKind.BASE, r.snapshots.get(0).board.getCell(0, 0).kind);
    assertEquals(CellKind.BASE, r.snapshots.get(0).board.getCell(7, 7).kind);
    assertEquals(CellKind.EMPTY, r.snapshots.get(0).board.getCell(0, 1).kind);

    // Last snapshot is non-initial: both earlier moves are on the board.
    var last = r.snapshots.get(2).board;
    assertNotEquals(CellKind.EMPTY, last.getCell(0, 1).kind);
    assertEquals(1, last.getCell(0, 1).owner);
    assertNotEquals(CellKind.EMPTY, last.getCell(7, 6).kind);
    assertEquals(2, last.getCell(7, 6).owner);

    // Snapshots are the board BEFORE the turn, so no neutrals were used anywhere here.
    for (GamesDbReplay.Snapshot s : r.snapshots) {
      assertTrue(!s.neutralUsed[0] && !s.neutralUsed[1]);
    }
  }

  @Test
  public void testNeutralActionMarksBudgetForFollowingSnapshots() {
    // A neutral pair must sit on the mover's OWN normal cells, so p1 grows two first.
    JsonNode t =
        turns(
            "["
                + "{\"player\":1,\"moves\":[{\"type\":\"place\",\"row\":0,\"col\":1},"
                + "{\"type\":\"place\",\"row\":1,\"col\":0}]},"
                + "{\"player\":2,\"moves\":[{\"type\":\"place\",\"row\":7,\"col\":6}]},"
                + "{\"player\":1,\"moves\":[{\"type\":\"neutral\",\"cells\":"
                + "[{\"row\":0,\"col\":1},{\"row\":1,\"col\":0}]}]},"
                + "{\"player\":2,\"moves\":[{\"type\":\"place\",\"row\":6,\"col\":6}]}"
                + "]");

    GamesDbReplay.Replay r = GamesDbReplay.replay(8, 8, t);

    assertNull(r.skipReason);
    assertTrue(!r.snapshots.get(2).neutralUsed[0], "budget not yet spent before the turn");
    assertTrue(r.snapshots.get(3).neutralUsed[0], "p1 neutral budget spent");
    assertTrue(!r.snapshots.get(3).neutralUsed[1]);
    assertEquals(CellKind.NEUTRAL, r.snapshots.get(3).board.getCell(0, 1).kind);
  }

  @Test
  public void testInitialBoardHasBothBases() {
    var b = GamesDbReplay.initialBoard(12, 12);
    assertEquals(1, b.getCell(0, 0).owner);
    assertEquals(2, b.getCell(11, 11).owner);
    assertEquals(CellKind.EMPTY, b.getCell(5, 5).kind);
  }

  /**
   * The replay must use the real server rules, not {@code SearchEngine.applyAction}: capturing an
   * opponent NORMAL cell FORTIFIES it, and a cell that loses base-connectivity STAYS on the board.
   * The erasing variant contradicts games.db — 503 recorded attacks target cells it has emptied.
   */
  @Test
  public void testCaptureFortifiesAndDisconnectedCellsSurvive() {
    // p1 chains base(0,0) -> (0,1) -> (0,2); p2 walks its diagonal (three actions per turn, as the
    // rules allow) and then captures the link at (0,1).
    JsonNode t =
        turns(
            "["
                + "{\"player\":1,\"moves\":[{\"type\":\"place\",\"row\":0,\"col\":1},"
                + "{\"type\":\"place\",\"row\":0,\"col\":2}]},"
                + "{\"player\":2,\"moves\":[{\"type\":\"place\",\"row\":6,\"col\":6},"
                + "{\"type\":\"place\",\"row\":5,\"col\":5},"
                + "{\"type\":\"place\",\"row\":4,\"col\":4}]},"
                + "{\"player\":1,\"moves\":[]},"
                + "{\"player\":2,\"moves\":[{\"type\":\"place\",\"row\":3,\"col\":3},"
                + "{\"type\":\"place\",\"row\":2,\"col\":2},"
                + "{\"type\":\"place\",\"row\":1,\"col\":1}]},"
                + "{\"player\":1,\"moves\":[]},"
                + "{\"player\":2,\"moves\":[{\"type\":\"attack\",\"row\":0,\"col\":1}]},"
                + "{\"player\":1,\"moves\":[]}"
                + "]");

    GamesDbReplay.Replay r = GamesDbReplay.replay(8, 8, t);

    assertNull(r.skipReason);
    var last = r.snapshots.get(6).board;
    assertEquals(2, last.getCell(0, 1).owner, "captured cell changes owner");
    assertEquals(CellKind.FORTIFIED, last.getCell(0, 1).kind, "capturing a NORMAL cell fortifies");
    // (0,2) is now cut off from p1's base — the real rules keep it on the board.
    assertEquals(1, last.getCell(0, 2).owner, "disconnected cell must not be erased");
    assertEquals(CellKind.NORMAL, last.getCell(0, 2).kind);
  }

  @Test
  public void testSkipMissingPlayer() {
    JsonNode t = turns("[{\"turn\":1,\"moves\":[{\"type\":\"place\",\"row\":0,\"col\":1}]}]");
    GamesDbReplay.Replay r = GamesDbReplay.replay(8, 8, t);
    assertEquals("no_player", r.skipReason);
    assertNull(r.snapshots);
  }

  @Test
  public void testSkipMultiplayer() {
    JsonNode t =
        turns(
            "[{\"player\":1,\"moves\":[]},"
                + "{\"player\":3,\"moves\":[{\"type\":\"place\",\"row\":4,\"col\":4}]}]");
    GamesDbReplay.Replay r = GamesDbReplay.replay(8, 8, t);
    assertEquals("multiplayer", r.skipReason);
    assertNull(r.snapshots);
  }

  @Test
  public void testSkipReplayError() {
    JsonNode t = turns("[{\"player\":1,\"moves\":[{\"type\":\"teleport\",\"row\":0,\"col\":1}]}]");
    GamesDbReplay.Replay r = GamesDbReplay.replay(8, 8, t);
    assertEquals("replay_error", r.skipReason);
    assertNull(r.snapshots);
  }

  /**
   * The turn-scoped rules only bite if {@code movesLeft} decrements across a turn. Rebuilding the
   * state per move (with {@code movesLeft} reset to ACTIONS_PER_TURN) replayed a mid-turn neutral —
   * which the server rejects, since a neutral pair is a turn-opening action — as legal.
   */
  @Test
  public void testSkipMidTurnNeutral() {
    JsonNode t =
        turns(
            "[{\"player\":1,\"moves\":[{\"type\":\"place\",\"row\":0,\"col\":1},"
                + "{\"type\":\"place\",\"row\":1,\"col\":0},"
                + "{\"type\":\"neutral\",\"cells\":[{\"row\":0,\"col\":1},{\"row\":1,\"col\":0}]}]}]");
    GamesDbReplay.Replay r = GamesDbReplay.replay(8, 8, t);
    assertEquals("illegal_move", r.skipReason);
    assertNull(r.snapshots);
  }

  /** Same root cause: a turn carrying more than ACTIONS_PER_TURN actions cannot exist. */
  @Test
  public void testSkipTurnWithTooManyActions() {
    JsonNode t =
        turns(
            "[{\"player\":1,\"moves\":[{\"type\":\"place\",\"row\":0,\"col\":1},"
                + "{\"type\":\"place\",\"row\":1,\"col\":0},"
                + "{\"type\":\"place\",\"row\":1,\"col\":1},"
                + "{\"type\":\"place\",\"row\":0,\"col\":2}]}]");
    GamesDbReplay.Replay r = GamesDbReplay.replay(8, 8, t);
    assertEquals("illegal_move", r.skipReason);
    assertNull(r.snapshots);
  }

  /** A neutral placement ends the turn, so an action recorded after it is out of rules too. */
  @Test
  public void testSkipActionAfterNeutralInSameTurn() {
    JsonNode t =
        turns(
            "[{\"player\":1,\"moves\":[{\"type\":\"place\",\"row\":0,\"col\":1},"
                + "{\"type\":\"place\",\"row\":1,\"col\":0},"
                + "{\"type\":\"place\",\"row\":1,\"col\":1}]},"
                + "{\"player\":2,\"moves\":[]},"
                + "{\"player\":1,\"moves\":[{\"type\":\"neutral\",\"cells\":"
                + "[{\"row\":0,\"col\":1},{\"row\":1,\"col\":0}]},"
                + "{\"type\":\"place\",\"row\":0,\"col\":2}]}]");
    GamesDbReplay.Replay r = GamesDbReplay.replay(8, 8, t);
    assertEquals("illegal_move", r.skipReason);
    assertNull(r.snapshots);
  }

  /** Three actions in one turn stay legal — the fix must not reject ordinary full turns. */
  @Test
  public void testFullThreeActionTurnIsLegal() {
    JsonNode t =
        turns(
            "[{\"player\":1,\"moves\":[{\"type\":\"place\",\"row\":0,\"col\":1},"
                + "{\"type\":\"place\",\"row\":1,\"col\":0},"
                + "{\"type\":\"place\",\"row\":1,\"col\":1}]},"
                + "{\"player\":2,\"moves\":[{\"type\":\"place\",\"row\":7,\"col\":6},"
                + "{\"type\":\"place\",\"row\":6,\"col\":7},"
                + "{\"type\":\"place\",\"row\":6,\"col\":6}]},"
                + "{\"player\":1,\"moves\":[]}]");
    GamesDbReplay.Replay r = GamesDbReplay.replay(8, 8, t);
    assertNull(r.skipReason);
    assertEquals(3, r.snapshots.size());
    assertEquals(1, r.snapshots.get(2).board.getCell(1, 1).owner);
    assertEquals(2, r.snapshots.get(2).board.getCell(6, 6).owner);
  }

  @Test
  public void testSkipMalformedNeutral() {
    JsonNode t =
        turns(
            "[{\"player\":1,\"moves\":[{\"type\":\"neutral\",\"cells\":"
                + "[{\"row\":3,\"col\":3}]}]}]");
    assertEquals("replay_error", GamesDbReplay.replay(8, 8, t).skipReason);
  }
}
