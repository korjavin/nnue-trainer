package com.engine.nnue_trainer.search;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.nnue.NNUEModel;
import com.engine.nnue_trainer.nnue.FeatureExtractor;

public class SearchEngine {

    private final NNUEModel nnueModel;

    public SearchEngine(NNUEModel nnueModel) {
        this.nnueModel = nnueModel;
    }

    /**
     * Alpha-Beta Minimax search implementation.
     *
     * @param board The current board state
     * @param depth The search depth remaining
     * @param alpha The alpha value for pruning
     * @param beta The beta value for pruning
     * @param player The current player
     * @param maximizingPlayer True if we want to maximize the score, False otherwise
     * @return The evaluation score
     */
    public float alphaBeta(Board board, int depth, float alpha, float beta, int player, boolean maximizingPlayer) {
        if (depth == 0) {
            float[] features = FeatureExtractor.extractFeatures(board);
            float score = nnueModel.forward(features);

            // Scale/normalize the NNUE raw score to a normalized minimax evaluation value using sigmoid
            // so it falls in a predictable range, e.g., [-1, 1] or [0, 1]
            return (float) (1.0 / (1.0 + Math.exp(-score / 400.0)));
        }

        // Search Framework (Recursive step) will be implemented as per search framework requirements
        // Returning a stub value for now as Move Generation is required for full search logic.
        return 0.0f;
    }
}
