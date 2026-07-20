package com.engine.nnue_trainer.search;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.nnue.NNUEModel;
import org.junit.jupiter.api.Test;



public class SearchEngineTest {

    @Test
    public void testAlphaBetaLeafNode() {
        NNUEModel model = NNUEModel.createDefault();
        SearchEngine engine = new SearchEngine(model);
        Board board = new Board(5, 5);

        float score = engine.alphaBeta(board, 0, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, 1, true);
        // We just want to ensure it executes without errors and returns a valid float.
        org.junit.jupiter.api.Assertions.assertTrue(score >= 0.0f && score <= 1.0f);
    }
}
