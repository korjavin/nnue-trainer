package com.engine.nnue_trainer.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.engine.nnue_trainer.search.gobot.GoBotSearcher.LeafEval;
import org.junit.jupiter.api.Test;

/** Task 2: the EVAL/SEARCH flags select the GoBot search's leaf eval. */
public class GobotLeafEvalFlagTest {

  @Test
  public void nnueOnlyWhenGobotSearchAndNnueEval() {
    assertEquals(LeafEval.NNUE, GameLoopHandler.gobotLeafEvalFor("GOBOT", "NNUE"));
    assertEquals(LeafEval.NNUE, GameLoopHandler.gobotLeafEvalFor("gobot", "nnue"));
  }

  @Test
  public void handTunedForEveryOtherCombo() {
    assertEquals(LeafEval.HAND_TUNED, GameLoopHandler.gobotLeafEvalFor("GOBOT", "HANDTUNED"));
    assertEquals(LeafEval.HAND_TUNED, GameLoopHandler.gobotLeafEvalFor("GOBOT", null));
    assertEquals(LeafEval.HAND_TUNED, GameLoopHandler.gobotLeafEvalFor(null, "NNUE"));
    assertEquals(LeafEval.HAND_TUNED, GameLoopHandler.gobotLeafEvalFor(null, null));
  }
}
