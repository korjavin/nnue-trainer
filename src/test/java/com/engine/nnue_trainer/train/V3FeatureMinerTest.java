package com.engine.nnue_trainer.train;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.v2.PatternContract;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Accumulation + discrimination-ranking math of the v3 feature miner. */
public class V3FeatureMinerTest {

  private static Board board12() {
    return new Board(V3FeatureMiner.BOARD, V3FeatureMiner.BOARD);
  }

  private static V3FeatureMiner.Feature find(List<V3FeatureMiner.Feature> fs, int r, int c, int s) {
    return fs.stream()
        .filter(f -> f.row == r && f.col == c && f.state == s)
        .findFirst()
        .orElseThrow(() -> new AssertionError("no feature " + r + "," + c + "," + s));
  }

  @Test
  public void testMeanEvalDiscriminationAndSupportFloor() {
    V3FeatureMiner.Stats stats = new V3FeatureMiner.Stats();

    // Two positions with a p1 stone at (3,4); one plain position. Evals 100, 200, 0 -> baseline
    // 100.
    Board withStone = board12();
    withStone.setCell(3, 4, new Cell(1, CellKind.NORMAL));
    stats.add(withStone, 1, 100);
    stats.add(withStone, 1, 200);
    stats.add(board12(), 1, 0);

    assertEquals(3, stats.positions());
    assertEquals(100.0, stats.baselineMeanEval(), 1e-9);

    List<V3FeatureMiner.Feature> fs = stats.ranked(2);

    // (3,4,NORMAL_SELF) is active in the two 100/200 positions: mean 150, discrimination 50.
    V3FeatureMiner.Feature stone = find(fs, 3, 4, PatternContract.NORMAL_SELF);
    assertEquals(2, stone.support);
    assertEquals(150.0, stone.meanEval, 1e-9);
    assertEquals(50.0, stone.discrimination, 1e-9);
    assertTrue(stone.rank >= 0);

    // The same cell EMPTY only occurs in the single eval-0 position: below the floor of 2.
    V3FeatureMiner.Feature empty = find(fs, 3, 4, PatternContract.EMPTY);
    assertEquals(1, empty.support);
    assertEquals(0.0, empty.meanEval, 1e-9);
    assertEquals(100.0, empty.discrimination, 1e-9);
    assertEquals(-1, empty.rank);

    // A cell untouched in every position is EMPTY in all 3 -> discrimination 0, still ranked.
    V3FeatureMiner.Feature everywhere = find(fs, 0, 0, PatternContract.EMPTY);
    assertEquals(3, everywhere.support);
    assertEquals(0.0, everywhere.discrimination, 1e-9);

    // Exactly one state is active per cell per position.
    long cell34 = fs.stream().filter(f -> f.row == 3 && f.col == 4).mapToLong(f -> f.support).sum();
    assertEquals(stats.positions(), cell34);
  }

  @Test
  public void testRankingIsByDiscriminationNotSupport() {
    V3FeatureMiner.Stats stats = new V3FeatureMiner.Stats();

    // (1,1) holds a stone in 8 low-swing positions; (2,2) in only 3 wildly-swinging ones.
    for (int i = 0; i < 8; i++) {
      Board b = board12();
      b.setCell(1, 1, new Cell(1, CellKind.NORMAL));
      stats.add(b, 1, 10);
    }
    for (int i = 0; i < 3; i++) {
      Board b = board12();
      b.setCell(2, 2, new Cell(1, CellKind.NORMAL));
      stats.add(b, 1, 1000);
    }

    List<V3FeatureMiner.Feature> fs = stats.ranked(3);
    V3FeatureMiner.Feature common = find(fs, 1, 1, PatternContract.NORMAL_SELF);
    V3FeatureMiner.Feature rare = find(fs, 2, 2, PatternContract.NORMAL_SELF);

    assertTrue(common.support > rare.support, "the low-discrimination feature is the common one");
    assertTrue(rare.discrimination > common.discrimination);
    assertTrue(rare.rank < common.rank, "rare-but-discriminative must outrank common-but-flat");

    // Ranks are dense, ordered by discrimination descending, and -1 only below the floor.
    double prev = Double.MAX_VALUE;
    int expected = 0;
    for (V3FeatureMiner.Feature f : fs) {
      if (f.rank < 0) {
        assertTrue(f.support < 3);
        continue;
      }
      assertEquals(expected++, f.rank);
      assertTrue(f.discrimination <= prev + 1e-9);
      prev = f.discrimination;
    }
  }

  @Test
  public void testEmptyStatsAndDefaultFloor() {
    V3FeatureMiner.Stats empty = new V3FeatureMiner.Stats();
    assertEquals(0, empty.positions());
    assertEquals(0.0, empty.baselineMeanEval(), 1e-9);
    assertTrue(empty.ranked(empty.defaultSupportFloor()).isEmpty());
    assertEquals(30, empty.defaultSupportFloor());

    // Floor default is ~1% of positions but never below 30.
    V3FeatureMiner.Stats many = new V3FeatureMiner.Stats();
    for (int i = 0; i < 5000; i++) {
      many.add(board12(), 1, 0);
    }
    assertEquals(50, many.defaultSupportFloor());
  }
}
