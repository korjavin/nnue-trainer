package com.engine.nnue_trainer.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.v2.NNUEv2Evaluator;
import com.engine.nnue_trainer.v2.PatternDictionary;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Opt-in guarantee: EVAL=NNUEV2 routes the leaf eval through the v2 evaluator, and with the flag
 * OFF (the default) the leaf eval is byte-identical to the pre-existing baseline. Uses an injected
 * synthetic evaluator that returns a distinctive constant, so no 344MB blob is needed.
 */
public class SearchEngineNnueV2Test {

  private static final float MARKER = 999.0f;

  /** Zero everything except l3 bias == MARKER, so every board evaluates to exactly MARKER. */
  private static NNUEv2Evaluator constantEvaluator() throws Exception {
    PatternDictionary dict =
        PatternDictionary.load(Path.of("python", "v2", "nnue_v2_dictionary.json"));
    int n = dict.numPatterns();
    int w = 4;
    float[][] stm = new float[n][w];
    float[][] nstm = new float[n][w];
    float[][] l1 = new float[16][2 * w + 14];
    float[][] l2 = new float[32][16];
    float[][] l3 = new float[1][32];
    return new NNUEv2Evaluator(
        dict, stm, nstm, l1, new float[16], l2, new float[32], l3, new float[] {MARKER});
  }

  /** 8x8 (non-12x12) board so the default path is the deterministic piece-count baseline. */
  private static Board board() {
    Board b = new Board(8, 8);
    b.setCell(0, 0, new Cell(1, CellKind.BASE));
    b.setCell(7, 7, new Cell(2, CellKind.BASE));
    b.setCell(2, 2, new Cell(1, CellKind.NORMAL));
    b.setCell(3, 3, new Cell(1, CellKind.NORMAL));
    b.setCell(5, 5, new Cell(2, CellKind.NORMAL));
    return b;
  }

  @Test
  public void flagOffLeavesBaselineUnchangedFlagOnRoutesToV2() throws Exception {
    SearchEngine engine = new SearchEngine();
    engine.setNnueV2Evaluator(constantEvaluator());
    Board b = board();

    // Default: flag OFF. v2 must NOT be consulted -> deterministic piece-count baseline (2 - 1).
    assertFalse(engine.isUseNnueV2Eval(), "v2 eval must default OFF");
    float baseline = engine.evaluate(b, null, 1, 1);
    assertEquals(
        2.0f - 1.0f, baseline, 0.0f, "flag OFF must be the unchanged piece-count baseline");
    assertNotEquals(MARKER, baseline, "flag OFF must not route through v2");

    // Flag ON: leaf eval routes through the injected v2 evaluator (constant MARKER).
    engine.setUseNnueV2Eval(true);
    float routed = engine.evaluate(b, null, 1, 1);
    assertEquals(MARKER, routed, 0.0f, "flag ON must route the leaf eval through the v2 evaluator");
  }
}
