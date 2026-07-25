package com.engine.nnue_trainer.search.gobot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.nnue.NNUEModel;
import com.engine.nnue_trainer.v2.NNUEv2Evaluator;
import com.engine.nnue_trainer.v2.PatternDictionary;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Task 1: swappable NNUE leaf eval in the GoBot search — scaling, clamping, orientation. */
public class GoBotNnueLeafTest {

  private static Board baseBoard() {
    Board b = new Board(12, 12);
    b.setCell(0, 0, new Cell(1, CellKind.BASE));
    b.setCell(11, 11, new Cell(2, CellKind.BASE));
    return b;
  }

  /** Constant-output net: all hidden weights/biases 0 → forward == outputBias for any input. */
  private static NNUEModel constModel(float output) {
    return new NNUEModel(new float[1][864], new float[1], new float[] {0f}, output);
  }

  /** Net whose output counts the mapped player's own NORMAL stones (feature stateIndex == 1). */
  private static NNUEModel ownStoneCountModel() {
    float[][] hidden = new float[1][864];
    for (int cell = 0; cell < 144; cell++) {
      hidden[0][cell * 6 + 1] = 1f; // own normal-stone feature
    }
    return new NNUEModel(hidden, new float[1], new float[] {1f}, 0f);
  }

  @AfterEach
  public void resetDefault() {
    GoBotSearcher.configureDefaultLeafEval(GoBotSearcher.LeafEval.HAND_TUNED, null);
  }

  @Test
  public void scalesOutputToInteger() {
    long v = GoBotSearcher.nnueLeaf(baseBoard(), 1, constModel(0.5f));
    assertEquals(Math.round(0.5 * GoBotSearcher.NNUE_SCALE), v);
  }

  @Test
  public void clampsStrictlyBelowMate() {
    long hi = GoBotSearcher.nnueLeaf(baseBoard(), 1, constModel(1e9f));
    long lo = GoBotSearcher.nnueLeaf(baseBoard(), 1, constModel(-1e9f));
    assertEquals(GoBotSearcher.NNUE_CLAMP, hi);
    assertEquals(-GoBotSearcher.NNUE_CLAMP, lo);
    assertTrue(hi < GoBotSearcher.MATE_SCORE, "never collides with mate");
    assertTrue(lo > -GoBotSearcher.MATE_SCORE);
  }

  @Test
  public void orientedToRequestedPlayer() {
    // Player 1 owns more NORMAL stones than player 2.
    Board b = baseBoard();
    b.setCell(1, 1, new Cell(1, CellKind.NORMAL));
    b.setCell(1, 2, new Cell(1, CellKind.NORMAL));
    b.setCell(1, 3, new Cell(1, CellKind.NORMAL));
    b.setCell(10, 10, new Cell(2, CellKind.NORMAL));
    NNUEModel m = ownStoneCountModel();
    long forP1 = GoBotSearcher.nnueLeaf(b, 1, m);
    long forP2 = GoBotSearcher.nnueLeaf(b, 2, m);
    assertTrue(forP1 > forP2, "higher = better for the mapped player (matches HandTunedEval)");
  }

  @Test
  public void searchWithNnueLeafRunsAndIsDeterministic() {
    GoBotSearcher.configureDefaultLeafEval(GoBotSearcher.LeafEval.NNUE, ownStoneCountModel());
    GoState state = GoState.fromBoard(baseBoard(), 1, 3, new boolean[4]);

    GoResult r1 = GoBotSearcher.chooseDepth(state, 3);
    GoResult r2 = GoBotSearcher.chooseDepth(state, 3);
    assertNotNull(r1, "NNUE-leaf search completes at fixed depth");
    assertNotNull(r2);
    assertEquals(r1.action, r2.action, "deterministic action");
    assertEquals(r1.score, r2.score, "deterministic score");
    assertTrue(r1.score < GoBotSearcher.MATE_SCORE && r1.score > -GoBotSearcher.MATE_SCORE);
  }

  /**
   * Zero-sum stub evaluator: a SIGNED normalized score from {@code stm}'s frame, driven by
   * NORMAL-stone count (bead d4a.4.5 — the net now regresses the raw score, so positive == good for
   * stm, negative == bad, 0 == even). Winner's perspective &gt; 0, loser's &lt; 0 (reflection), so it
   * exercises nnueV2Leaf's orientation + de-normalization without the 344MB net.
   */
  private static NNUEv2Evaluator stoneCountV2() throws Exception {
    PatternDictionary dict =
        PatternDictionary.load(Path.of("python", "v2", "nnue_v2_dictionary.json"));
    int n = dict.numPatterns();
    // Minimal valid weights (w=1): forward math is unused — evaluate() is overridden below.
    float[][] embed = new float[n][1];
    float[][] l1 = new float[16][2 * 1 + 14];
    float[][] l2 = new float[32][16];
    float[][] l3 = new float[1][32];
    return new NNUEv2Evaluator(
        dict, embed, embed, l1, new float[16], l2, new float[32], l3, new float[1]) {
      @Override
      public float evaluate(Board b, int stm) {
        int mine = 0;
        int theirs = 0;
        for (int r = 0; r < 12; r++) {
          for (int c = 0; c < 12; c++) {
            Cell cell = b.getCell(r, c);
            if (cell == null || cell.kind != CellKind.NORMAL) {
              continue;
            }
            if (cell.owner == stm) {
              mine++;
            } else if (cell.owner != 0) {
              theirs++;
            }
          }
        }
        return 0.9f * Math.signum(mine - theirs); // signed score: +0.9 winner / -0.9 loser
      }
    };
  }

  @Test
  public void v2LeafSignSurvivesScaleEnv() throws Exception {
    // Player 1 clearly winning (three NORMAL stones to player 2's zero).
    Board b = baseBoard();
    b.setCell(1, 1, new Cell(1, CellKind.NORMAL));
    b.setCell(1, 2, new Cell(1, CellKind.NORMAL));
    b.setCell(1, 3, new Cell(1, CellKind.NORMAL));
    NNUEv2Evaluator v2 = stoneCountV2();

    long forWinner = GoBotSearcher.nnueV2Leaf(b, 1, v2); // winning STM
    long forMirror = GoBotSearcher.nnueV2Leaf(b, 2, v2); // reflected perspective

    assertTrue(forWinner > 500, "winning STM position maps to a strongly-positive leaf: " + forWinner);
    assertTrue(forMirror < -500, "its mirror maps to a strongly-negative leaf: " + forMirror);
    assertEquals(forWinner, -forMirror, "zero-sum: winner and mirror are exact negatives");
  }

  @Test
  public void defaultLeafIsHandTuned() {
    GoBotSearcher s =
        GoBotSearcher.newSearcher(GoState.fromBoard(baseBoard(), 1, 3, new boolean[4]));
    assertEquals(GoBotSearcher.LeafEval.HAND_TUNED, s.leafMode, "default preserves parity path");
  }
}
