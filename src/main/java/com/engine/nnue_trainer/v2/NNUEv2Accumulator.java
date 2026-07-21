package com.engine.nnue_trainer.v2;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import java.util.Map;
import java.util.Arrays;

public class NNUEv2Accumulator {

    private final Map<Long, float[]> stmWeights;
    private final Map<Long, float[]> nstmWeights;
    private final float[] denseBiases;
    private final int k;

    public NNUEv2Accumulator(Map<Long, float[]> stmWeights, Map<Long, float[]> nstmWeights, float[] denseBiases, int k) {
        this.stmWeights = stmWeights;
        this.nstmWeights = nstmWeights;
        this.denseBiases = Arrays.copyOf(denseBiases, denseBiases.length);
        this.k = k;
    }

    public long extractPattern(Board board, int centerR, int centerC, boolean stmPerspective) {
        long patternId = 0;
        for (int r = centerR - 2; r <= centerR + 2; r++) {
            for (int c = centerC - 2; c <= centerC + 2; c++) {
                int val = -1; // Out of bounds
                if (r >= 0 && r < board.rows && c >= 0 && c < board.cols) {
                    Cell cell = board.getCell(r, c);
                    val = getCellState(cell);
                    if (!stmPerspective) {
                        val = flipPerspective(val);
                    }
                }
                patternId = patternId * 7 + (val + 1);
            }
        }
        return patternId;
    }

    private int getCellState(Cell cell) {
        if (cell == null || cell.kind == com.engine.nnue_trainer.board.CellKind.EMPTY) {
            return 0; // EMPTY
        }
        // Base mapping is currently ignored in v2 reference if it follows v1, but we might just use 1=Us, 2=Them, etc.
        // Assuming cell.owner == 1 (STM) and 2 (NSTM).
        // For simplicity let's stick to the 1-5 mapping.
        if (cell.kind == com.engine.nnue_trainer.board.CellKind.NORMAL) {
            if (cell.owner == 1) return 1;
            if (cell.owner == 2) return 2;
        } else if (cell.kind == com.engine.nnue_trainer.board.CellKind.FORTIFIED) {
            if (cell.owner == 1) return 3;
            if (cell.owner == 2) return 4;
        } else if (cell.kind == com.engine.nnue_trainer.board.CellKind.NEUTRAL) {
            return 5;
        }
        return 0;
    }

    private int flipPerspective(int val) {
        if (val == 1) return 2;
        if (val == 2) return 1;
        if (val == 3) return 4;
        if (val == 4) return 3;
        return val;
    }

    public float[] forward(Board board, float[] dense14) {
        float[] accumStm = Arrays.copyOf(denseBiases, denseBiases.length);
        float[] accumNstm = Arrays.copyOf(denseBiases, denseBiases.length);

        for (int r = 0; r < board.rows; r++) {
            for (int c = 0; c < board.cols; c++) {
                long stmPatternId = extractPattern(board, r, c, true);
                if (stmWeights.containsKey(stmPatternId)) {
                    float[] weights = stmWeights.get(stmPatternId);
                    for (int i = 0; i < k; i++) {
                        accumStm[i] += weights[i];
                    }
                }

                long nstmPatternId = extractPattern(board, r, c, false);
                if (nstmWeights.containsKey(nstmPatternId)) {
                    float[] weights = nstmWeights.get(nstmPatternId);
                    for (int i = 0; i < k; i++) {
                        accumNstm[i] += weights[i];
                    }
                }
            }
        }

        float[] output = new float[k * 2 + 14];
        System.arraycopy(accumStm, 0, output, 0, k);
        System.arraycopy(accumNstm, 0, output, k, k);
        System.arraycopy(dense14, 0, output, k * 2, 14);

        return output;
    }
}
