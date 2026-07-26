package com.engine.nnue_trainer.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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

  /** An EVAL the GoBot leaf ignores must say so, or a harness reports hand-tuned results as v3. */
  @Test
  public void unwiredEvalWarns() {
    assertNotNull(GameLoopHandler.unwiredEvalWarning("GOBOT", "NNUEV3"));
    assertNotNull(GameLoopHandler.unwiredEvalWarning(null, "NNUEV2")); // SEARCH defaults to GOBOT
    assertNotNull(GameLoopHandler.unwiredEvalWarning("GOBOT", "NNEU")); // typo
  }

  @Test
  public void wiredOrIrrelevantEvalIsSilent() {
    assertNull(GameLoopHandler.unwiredEvalWarning("GOBOT", "NNUE"));
    assertNull(GameLoopHandler.unwiredEvalWarning("GOBOT", "HANDTUNED"));
    assertNull(GameLoopHandler.unwiredEvalWarning("GOBOT", null));
    assertNull(GameLoopHandler.unwiredEvalWarning("NEGAMAX", "NNUEV3")); // SearchEngine handles it
  }
}
