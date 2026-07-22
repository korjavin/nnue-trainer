package com.engine.nnue_trainer.v2;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.nnue.Accumulator;
import com.engine.nnue_trainer.search.SearchEngine;
import com.engine.nnue_trainer.nnue.NNUEModel;
import java.util.HashMap;

public class NNUEv2Evaluator extends SearchEngine {

  private NNUEv2Accumulator v2Accumulator;
  private float[] outputWeights;
  private float outputBias;

  public NNUEv2Evaluator() {
    super();
    initV2Weights();
  }

  public NNUEv2Evaluator(NNUEModel nnueModel) {
    super(nnueModel);
    initV2Weights();
  }

  private void initV2Weights() {
    // Initialize dummy weights since no trained V2 JSON exists in the repo yet.
    // K = 256, dense = 14
    int K = 256;
    int denseSize = 14;
    v2Accumulator =
        new NNUEv2Accumulator(new HashMap<>(), new float[1][K], new float[K], K, denseSize);
    outputWeights = new float[K * 2 + denseSize];
    outputBias = 0.0f;
  }

  public float evaluateBoard(Board board, int player, boolean maximizingPlayer) {
    return evaluate(board, null, player, maximizingPlayer);
  }

  @Override
  protected float evaluate(
      Board board, Accumulator accumulator, int player, boolean maximizingPlayer) {
    if (!"true".equals(System.getProperty("USE_NNUE_V2"))) {
      return super.evaluate(board, accumulator, player, maximizingPlayer);
    }

    nodesEvaluated++;
    int originalPlayer = maximizingPlayer ? player : getOpponent(player);
    int opponent = getOpponent(originalPlayer);

    boolean myBaseAlive = false;
    boolean oppBaseAlive = false;
    boolean hasBases = false;
    int myPieces = 0;
    int oppPieces = 0;

    for (int r = 0; r < board.rows; r++) {
      for (int c = 0; c < board.cols; c++) {
        Cell cell = board.getCell(r, c);
        if (cell != null) {
          if (cell.kind == CellKind.BASE) {
            hasBases = true;
            if (cell.owner == originalPlayer) myBaseAlive = true;
            else if (cell.owner == opponent) oppBaseAlive = true;
          }
          if (cell.kind != CellKind.EMPTY && cell.kind != CellKind.NEUTRAL) {
            if (cell.owner == originalPlayer) {
              myPieces++;
            } else if (cell.owner == opponent) {
              oppPieces++;
            }
          }
        }
      }
    }

    if (hasBases) {
      if (!myBaseAlive) return Float.NEGATIVE_INFINITY;
      if (!oppBaseAlive) return Float.POSITIVE_INFINITY;
    }

    // Full computation for V2
    float[] dense = DenseFeatures.extract(board, originalPlayer, 0);
    float[] hidden = v2Accumulator.computeFull(board, originalPlayer, dense);

    // Network forward pass
    float output = outputBias;
    int K = 256;
    for (int i = 0; i < hidden.length; i++) {
      float val = hidden[i];
      // Apply Clipped ReLU only to the accumulated hidden layers (first 2*K features)
      if (i < 2 * K) {
        val = Math.max(0.0f, Math.min(127.0f, val));
      }
      output += outputWeights[i] * val;
    }

    return output;
  }
}
