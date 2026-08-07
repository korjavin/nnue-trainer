package com.engine.nnue_trainer.train;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.engine.nnue_trainer.mcts.PolicyNetPrior;
import com.engine.nnue_trainer.search.ordering.PolicyOrderingTable;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Probe smoke: 20 real positions end-to-end. Skips when the local games.db corpus is absent. */
class OrderingQualityProbeTest {

  @Test
  void probeRunsOnTwentyPositions() throws Exception {
    Path db = Path.of(System.getenv().getOrDefault("NNUE_GAMES_DB", "games.db"));
    assumeTrue(Files.exists(db), "games.db corpus not available: " + db);
    Path weights = Path.of("ordering_policy.json");
    assumeTrue(Files.exists(weights), "ordering table artifact missing");

    PolicyOrderingTable table = PolicyOrderingTable.load(weights);
    Path convPath = Path.of("mcts_policy.json");
    PolicyNetPrior conv = Files.exists(convPath) ? PolicyNetPrior.load(convPath) : null;

    OrderingQualityProbe.Result r = OrderingQualityProbe.run(db, 20, table, conv);
    assertTrue(r.positions == 20, "probed 20 positions, got " + r.positions);
    assertTrue(r.meanRankTable >= 1.0, "mean rank is 1-based");
    assertTrue(r.meanRankRandom >= 1.0, "random mean rank sane");
    assertTrue(r.top1Table >= 0 && r.top1Table <= 1, "top-1 is a rate");
    assertTrue(r.top3Table >= r.top1Table, "top-3 dominates top-1");
    assertTrue(r.nanosPerScore > 0, "microbenchmark ran");
    System.out.printf(
        "probe smoke: mean rank table %.2f vs random %.2f, top-1 %.0f%%, %.2f µs/position%n",
        r.meanRankTable, r.meanRankRandom, 100 * r.top1Table, r.nanosPerScore / 1000.0);
  }
}
