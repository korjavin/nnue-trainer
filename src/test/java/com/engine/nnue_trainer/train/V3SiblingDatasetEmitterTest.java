package com.engine.nnue_trainer.train;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Shape of the sibling-group dataset: 144 valid feature ids per child, one group per position. */
public class V3SiblingDatasetEmitterTest {

  private static final int N_FEATURES =
      V3FeatureMiner.BOARD * V3FeatureMiner.BOARD * V3FeatureMiner.STATES;

  private static GamesDbReplay.Snapshot openingSnapshot() {
    Board board = GamesDbReplay.initialBoard(V3FeatureMiner.BOARD, V3FeatureMiner.BOARD);
    return new GamesDbReplay.Snapshot(board, 1, new boolean[2]);
  }

  /**
   * The opening cannot produce a turn-ending child: a neutral placement needs two NORMAL cells the
   * mover owns, and at the opening it owns only its base. Give player 1 two normals so both kinds
   * of child exist.
   */
  private static GamesDbReplay.Snapshot snapshotWithNeutralsAvailable() {
    Board board = GamesDbReplay.initialBoard(V3FeatureMiner.BOARD, V3FeatureMiner.BOARD);
    board.setCell(0, 1, new Cell(1, CellKind.NORMAL));
    board.setCell(1, 0, new Cell(1, CellKind.NORMAL));
    return new GamesDbReplay.Snapshot(board, 1, new boolean[2]);
  }

  @Test
  public void testEveryChildHasOneValidFeaturePerCell() {
    List<V3SiblingDatasetEmitter.Child> kids = V3SiblingDatasetEmitter.children(openingSnapshot());
    assertTrue(
        kids.size() >= V3SiblingDatasetEmitter.MIN_CHILDREN,
        "opening position must have an orderable sibling group, got " + kids.size());
    for (V3SiblingDatasetEmitter.Child c : kids) {
      assertEquals(144, c.active.length);
      Set<Integer> cells = new HashSet<>();
      for (int id : c.active) {
        assertTrue(id >= 0 && id < N_FEATURES, "feature id out of range: " + id);
        // idx(r,c,s) = (r*12+c)*8 + s, so id/8 is the cell: exactly one state per cell.
        assertTrue(cells.add(id / V3FeatureMiner.STATES), "two features for cell " + (id / 8));
      }
      assertEquals(144, cells.size());
    }
  }

  /**
   * The frame fix. An ordinary move keeps the mover (s=+1); a neutral placement ends the turn and
   * hands it to the opponent (s=-1). If children() ever goes back to scoring everything from the
   * parent's mover, every sign here collapses to +1 and this fails.
   */
  @Test
  public void testTurnEndingChildrenAreEmittedInTheFlippedFrame() {
    List<V3SiblingDatasetEmitter.Child> kids =
        V3SiblingDatasetEmitter.children(snapshotWithNeutralsAvailable());
    long neg = kids.stream().filter(c -> c.sign < 0).count();
    long pos = kids.stream().filter(c -> c.sign > 0).count();
    assertTrue(neg > 0, "no turn-flipping child emitted; the runtime frame is not being followed");
    assertTrue(pos > 0, "no same-mover child emitted");
  }

  @Test
  public void testRowsOfOneGroupShareGameAndPosId() throws Exception {
    List<V3SiblingDatasetEmitter.Child> kids = V3SiblingDatasetEmitter.children(openingSnapshot());
    ObjectMapper mapper = new ObjectMapper();
    for (V3SiblingDatasetEmitter.Child c : kids) {
      JsonNode n = mapper.readTree(V3SiblingDatasetEmitter.row("game-a", 7L, c));
      assertEquals("game-a", n.get("game_id").asText());
      assertEquals(7L, n.get("pos_id").asLong());
      assertEquals(144, n.get("active").size());
      assertTrue(n.hasNonNull("ht"));
      assertEquals(1, Math.abs(n.get("s").asInt()), "sign must be +-1");
    }
    // Siblings must not all score the same, or the group teaches no ordering at all.
    long distinct = kids.stream().map(c -> c.ht).distinct().count();
    assertTrue(distinct > 1, "sibling scores are constant; nothing to rank");
  }
}
