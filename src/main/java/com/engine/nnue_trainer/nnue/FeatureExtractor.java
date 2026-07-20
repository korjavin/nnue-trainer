package com.engine.nnue_trainer.nnue;

import com.engine.nnue_trainer.board.Board;

public class FeatureExtractor {

    /**
     * Extracts a 104-length feature vector from the given Board for the NNUE model.
     * Currently returns an array of zeros as a stub for feature extraction.
     *
     * @param board The game board to extract features from
     * @return A float array of 104 features
     */
    public static float[] extractFeatures(Board board) {
        // According to task 9: 26 features per player * 4 players = 104 features.
        // For now, return a zero-initialized array of size 104.
        return new float[104];
    }
}
