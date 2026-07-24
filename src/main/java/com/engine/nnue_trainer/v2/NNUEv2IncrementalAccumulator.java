package com.engine.nnue_trainer.v2;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.board.Pos;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class NNUEv2IncrementalAccumulator {

  public static void updateAccumulator(
      NNUEv2Accumulator accumulator,
      Board oldBoard,
      Board newBoard,
      List<Pos> modifiedPositions,
      float[] accumSTM,
      float[] accumNSTM,
      int activePlayer) {

    int nstmPlayer = 3 - activePlayer;
    int K = accumSTM.length;

    Set<Pos> affectedCenters = new HashSet<>();
    for (Pos p : modifiedPositions) {
      for (int dr = -2; dr <= 2; dr++) {
        for (int dc = -2; dc <= 2; dc++) {
          int cr = p.row + dr;
          int cc = p.col + dc;
          if (oldBoard.isValidPos(cr, cc)) {
            affectedCenters.add(new Pos(cr, cc));
          }
        }
      }
    }

    for (Pos center : affectedCenters) {
      int cr = center.row;
      int cc = center.col;

      // Old board
      Cell oldCell = oldBoard.getCell(cr, cc);
      if (oldCell != null && oldCell.kind != CellKind.EMPTY && oldCell.kind != CellKind.BASE) {
        List<Integer> oldPatternSTM = accumulator.extractPattern(oldBoard, cr, cc, activePlayer);
        float[] oldWeightsSTM = accumulator.getPatternWeights(oldPatternSTM);
        if (oldWeightsSTM != null) {
          for (int i = 0; i < K; i++) accumSTM[i] -= oldWeightsSTM[i];
        }

        List<Integer> oldPatternNSTM = accumulator.extractPattern(oldBoard, cr, cc, nstmPlayer);
        float[] oldWeightsNSTM = accumulator.getPatternWeights(oldPatternNSTM);
        if (oldWeightsNSTM != null) {
          for (int i = 0; i < K; i++) accumNSTM[i] -= oldWeightsNSTM[i];
        }
      }

      // New board
      Cell newCell = newBoard.getCell(cr, cc);
      if (newCell != null && newCell.kind != CellKind.EMPTY && newCell.kind != CellKind.BASE) {
        List<Integer> newPatternSTM = accumulator.extractPattern(newBoard, cr, cc, activePlayer);
        float[] newWeightsSTM = accumulator.getPatternWeights(newPatternSTM);
        if (newWeightsSTM != null) {
          for (int i = 0; i < K; i++) accumSTM[i] += newWeightsSTM[i];
        }

        List<Integer> newPatternNSTM = accumulator.extractPattern(newBoard, cr, cc, nstmPlayer);
        float[] newWeightsNSTM = accumulator.getPatternWeights(newPatternNSTM);
        if (newWeightsNSTM != null) {
          for (int i = 0; i < K; i++) accumNSTM[i] += newWeightsNSTM[i];
        }
      }
    }
  }
}
