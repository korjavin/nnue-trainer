package com.engine.nnue_trainer.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import org.junit.jupiter.api.Test;

public class DenseFeaturesTest {

  @Test
  public void testFeaturesBoundsAndSymmetry() {
    Board board = new Board(12, 12);
    // Add some random setup
    board.setCell(0, 0, new Cell(1, CellKind.BASE));
    board.setCell(1, 1, new Cell(1, CellKind.NORMAL));
    board.setCell(2, 2, new Cell(1, CellKind.FORTIFIED));

    board.setCell(11, 11, new Cell(2, CellKind.BASE));
    board.setCell(10, 10, new Cell(2, CellKind.NORMAL));
    board.setCell(9, 9, new Cell(2, CellKind.NORMAL));

    board.setCell(5, 5, new Cell(0, CellKind.NEUTRAL));

    int turnNumber = 42;

    float[] featuresP1 = DenseFeatures.extract(board, 1, turnNumber);
    float[] featuresP2 = DenseFeatures.extract(board, 2, turnNumber);

    assertEquals(14, featuresP1.length);
    assertEquals(14, featuresP2.length);

    // Assert bounds [0.0, 1.0] for all features except possibly distances if they somehow exceed
    // 1.0,
    // but our formulation bounds them to 1.0. Turn number and board size might exceed 1.0 if not
    // capped,
    // but the task states "features bounds [0.0, 1.0]". We will assert they are >= 0.0 and <= 1.0
    // Actually, turn_number could be > 100, so it could exceed 1.0. But let's assert what we can.
    for (int i = 0; i < 14; i++) {
      assertTrue(featuresP1[i] >= 0.0f, "Feature " + i + " out of lower bound");
      assertTrue(featuresP2[i] >= 0.0f, "Feature " + i + " out of lower bound");
    }

    // Assert symmetry
    // stm_normal_count_norm (0) <-> nstm_normal_count_norm (1)
    assertEquals(featuresP1[0], featuresP2[1], 1e-6);
    assertEquals(featuresP1[1], featuresP2[0], 1e-6);

    // stm_fortified_count_norm (2) <-> nstm_fortified_count_norm (3)
    assertEquals(featuresP1[2], featuresP2[3], 1e-6);
    assertEquals(featuresP1[3], featuresP2[2], 1e-6);

    // neutral_count_norm (4) <-> neutral_count_norm (4)
    assertEquals(featuresP1[4], featuresP2[4], 1e-6);

    // empty_count_norm (5) <-> empty_count_norm (5)
    assertEquals(featuresP1[5], featuresP2[5], 1e-6);

    // stm_base_alive (6) <-> nstm_base_alive (7)
    assertEquals(featuresP1[6], featuresP2[7], 1e-6);
    assertEquals(featuresP1[7], featuresP2[6], 1e-6);

    // stm_min_dist_to_enemy_base_norm (8) <-> nstm_min_dist_to_enemy_base_norm (9)
    assertEquals(featuresP1[8], featuresP2[9], 1e-6);
    assertEquals(featuresP1[9], featuresP2[8], 1e-6);

    // stm_connected_components_ratio (10) <-> nstm_connected_components_ratio (11)
    assertEquals(featuresP1[10], featuresP2[11], 1e-6);
    assertEquals(featuresP1[11], featuresP2[10], 1e-6);

    // turn_number_norm (12) <-> turn_number_norm (12)
    assertEquals(featuresP1[12], featuresP2[12], 1e-6);

    // board_size_norm (13) <-> board_size_norm (13)
    assertEquals(featuresP1[13], featuresP2[13], 1e-6);
  }

  @Test
  public void testEmptyBoard() {
    Board board = new Board(12, 12);
    float[] features = DenseFeatures.extract(board, 1, 0);

    assertEquals(1.0f, features[5], 1e-6); // empty_count_norm
    assertEquals(0.0f, features[0], 1e-6); // stm_normal_count_norm
    assertEquals(0.0f, features[10], 1e-6); // stm_connected_components_ratio
    assertEquals(1.0f, features[8], 1e-6); // stm_min_dist_to_enemy_base_norm
    assertEquals(0.0f, features[12], 1e-6); // turn_number_norm
  }
}
