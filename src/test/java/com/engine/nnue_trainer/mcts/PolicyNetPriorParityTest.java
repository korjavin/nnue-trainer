package com.engine.nnue_trainer.mcts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.engine.nnue_trainer.board.Action;
import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.board.PlaceNeutralsAction;
import com.engine.nnue_trainer.search.gobot.GoState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the Java conv forward against head outputs computed by {@code train_policy.py} from the same
 * weights file — the same export/parity-fixture discipline as {@code V3NetParityTest}. A drift
 * between the python trainer's encoding/arch and this loader must fail here, not show up as
 * silently-wrong priors in a gauntlet.
 */
class PolicyNetPriorParityTest {

  private static final Path WEIGHTS = Path.of("mcts_policy.json");
  private static final Path FIXTURE =
      Path.of("src", "test", "resources", "mcts", "mcts_policy_parity.json");

  /** float32 training vs double inference over 4 conv layers: sub-1e-3 agreement expected. */
  private static final double TOL = 1e-3;

  @Test
  void headsMatchPythonFixture() throws Exception {
    assertTrue(Files.exists(WEIGHTS), "trained weights artifact missing: " + WEIGHTS);
    assertTrue(Files.exists(FIXTURE), "parity fixture missing: " + FIXTURE);
    PolicyNetPrior prior = PolicyNetPrior.load(WEIGHTS);
    JsonNode fixture = new ObjectMapper().readTree(Files.readAllBytes(FIXTURE));
    assertEquals(fixture.get("pair_bias").doubleValue(), prior.pairBias(), TOL);

    JsonNode samples = fixture.get("samples");
    assertTrue(samples.isArray() && samples.size() > 0, "fixture has samples");
    for (JsonNode sample : samples) {
      int[] sym = new int[144];
      for (int i = 0; i < 144; i++) {
        sym[i] = sample.get("sym").get(i).asInt();
      }
      PolicyNetPrior.Heads heads =
          prior.forward(
              sym, sample.get("ml").asInt(), sample.get("nuo").asInt(), sample.get("nux").asInt());
      for (int i = 0; i < 144; i++) {
        assertEquals(
            sample.get("move_logits").get(i).doubleValue(), heads.move[i], TOL, "move logit " + i);
        assertEquals(sample.get("pair_u").get(i).doubleValue(), heads.pairU[i], TOL, "pair u " + i);
      }
    }
  }

  @Test
  void priorsAreAMaskedDistributionOverLegalActions() throws Exception {
    assertTrue(Files.exists(WEIGHTS), "trained weights artifact missing: " + WEIGHTS);
    PolicyNetPrior prior = PolicyNetPrior.load(WEIGHTS);

    Board board = new Board(12, 12);
    board.setCell(0, 0, new Cell(1, CellKind.BASE));
    board.setCell(11, 11, new Cell(2, CellKind.BASE));
    board.setCell(0, 1, new Cell(1, CellKind.NORMAL));
    board.setCell(1, 1, new Cell(1, CellKind.NORMAL));
    GoState state = GoState.fromBoard(board, 1, 3, new boolean[2]);
    List<Action> legal = state.legalActions();
    assertTrue(
        legal.stream().anyMatch(a -> a instanceof PlaceNeutralsAction),
        "fixture position must offer a neutral pair");

    float[] p = prior.priors(state, legal);
    assertEquals(legal.size(), p.length);
    double sum = 0;
    for (float v : p) {
      assertTrue(v > 0, "every legal action gets positive prior mass");
      sum += v;
    }
    assertEquals(1.0, sum, 1e-5, "softmax over legal actions normalizes");
  }

  /** End-to-end: the searcher runs on the trained prior (and prints its sims/s). */
  @Test
  void mctsRunsOnTrainedPrior() throws Exception {
    assertTrue(Files.exists(WEIGHTS), "trained weights artifact missing: " + WEIGHTS);
    Board board = new Board(12, 12);
    board.setCell(0, 0, new Cell(1, CellKind.BASE));
    board.setCell(11, 11, new Cell(2, CellKind.BASE));
    board.setCell(0, 1, new Cell(1, CellKind.NORMAL));
    board.setCell(1, 1, new Cell(1, CellKind.NORMAL));
    board.setCell(10, 10, new Cell(2, CellKind.NORMAL));
    GoState state = GoState.fromBoard(board, 1, 3, new boolean[2]);

    MctsSearcher.Config cfg = new MctsSearcher.Config();
    cfg.prior = PolicyNetPrior.load(WEIGHTS);
    new MctsSearcher(state, cfg).runSims(300); // JIT warmup — measure steady state
    MctsSearcher s = new MctsSearcher(state, cfg);
    long t0 = System.nanoTime();
    s.runSims(500);
    double secs = (System.nanoTime() - t0) / 1e9;
    assertTrue(state.legalActions().contains(s.bestAction()), "net-prior search picks legally");
    System.out.printf(
        "MCTS net-prior smoke: %d sims in %.3fs (%.0f sims/s)%n",
        s.simsRun(), secs, s.simsRun() / secs);
  }
}
