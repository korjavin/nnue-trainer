package com.engine.nnue_trainer.v2;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;

public class DenseFeatures {

  private static int getManhattanDist(int r1, int c1, int r2, int c2) {
    return Math.abs(r1 - r2) + Math.abs(c1 - c2);
  }

  private static float getCcRatio(List<int[]> pieces) {
    if (pieces.isEmpty()) {
      return 0.0f;
    }

    Set<String> pieceSet = new HashSet<>();
    for (int[] p : pieces) {
      pieceSet.add(p[0] + "," + p[1]);
    }

    Set<String> visited = new HashSet<>();
    int numCcs = 0;

    for (int[] p : pieces) {
      String key = p[0] + "," + p[1];
      if (!visited.contains(key)) {
        numCcs++;
        Queue<int[]> queue = new LinkedList<>();
        queue.add(p);
        visited.add(key);

        while (!queue.isEmpty()) {
          int[] curr = queue.poll();
          int currR = curr[0];
          int currC = curr[1];

          for (int dr = -1; dr <= 1; dr++) {
            for (int dc = -1; dc <= 1; dc++) {
              if (dr == 0 && dc == 0) continue;
              int nr = currR + dr;
              int nc = currC + dc;
              String nKey = nr + "," + nc;
              if (pieceSet.contains(nKey) && !visited.contains(nKey)) {
                visited.add(nKey);
                queue.add(new int[] {nr, nc});
              }
            }
          }
        }
      }
    }

    return numCcs > 0 ? 1.0f / numCcs : 0.0f;
  }

  public static float[] extract(Board board, int stm, int turnNumber) {
    int rows = board.rows;
    int cols = board.cols;
    int area = rows * cols;

    int nstm = 3 - stm;

    int stmNormal = 0;
    int nstmNormal = 0;
    int stmFort = 0;
    int nstmFort = 0;
    int neutral = 0;
    int empty = 0;

    int[] stmBasePos = null;
    int[] nstmBasePos = null;
    float stmBaseAlive = 0.0f;
    float nstmBaseAlive = 0.0f;

    List<int[]> stmPieces = new ArrayList<>();
    List<int[]> nstmPieces = new ArrayList<>();

    for (int r = 0; r < rows; r++) {
      for (int c = 0; c < cols; c++) {
        Cell cell = board.getCell(r, c);
        if (cell == null || cell.kind == CellKind.EMPTY) {
          empty++;
        } else if (cell.kind == CellKind.NEUTRAL) {
          neutral++;
        } else if (cell.kind == CellKind.BASE) {
          if (cell.owner == stm) {
            stmBaseAlive = 1.0f;
            stmBasePos = new int[] {r, c};
            stmPieces.add(new int[] {r, c});
          } else if (cell.owner == nstm) {
            nstmBaseAlive = 1.0f;
            nstmBasePos = new int[] {r, c};
            nstmPieces.add(new int[] {r, c});
          }
        } else if (cell.kind == CellKind.NORMAL) {
          if (cell.owner == stm) {
            stmNormal++;
            stmPieces.add(new int[] {r, c});
          } else if (cell.owner == nstm) {
            nstmNormal++;
            nstmPieces.add(new int[] {r, c});
          }
        } else if (cell.kind == CellKind.FORTIFIED) {
          if (cell.owner == stm) {
            stmFort++;
            stmPieces.add(new int[] {r, c});
          } else if (cell.owner == nstm) {
            nstmFort++;
            nstmPieces.add(new int[] {r, c});
          }
        }
      }
    }

    float maxDist = (rows > 0 && cols > 0) ? (rows + cols - 2) : 1.0f;

    float stmMinDist = 1.0f;
    if (nstmBasePos != null && !stmPieces.isEmpty()) {
      float minDist = Float.MAX_VALUE;
      for (int[] p : stmPieces) {
        float dist = getManhattanDist(p[0], p[1], nstmBasePos[0], nstmBasePos[1]);
        if (dist < minDist) minDist = dist;
      }
      stmMinDist = minDist / maxDist;
    }

    float nstmMinDist = 1.0f;
    if (stmBasePos != null && !nstmPieces.isEmpty()) {
      float minDist = Float.MAX_VALUE;
      for (int[] p : nstmPieces) {
        float dist = getManhattanDist(p[0], p[1], stmBasePos[0], stmBasePos[1]);
        if (dist < minDist) minDist = dist;
      }
      nstmMinDist = minDist / maxDist;
    }

    float stmCcRatio = getCcRatio(stmPieces);
    float nstmCcRatio = getCcRatio(nstmPieces);

    float turnNumberNorm = turnNumber / 100.0f;
    float boardSizeNorm = area / 144.0f;

    float[] features = new float[14];
    features[0] = area > 0 ? (float) stmNormal / area : 0.0f;
    features[1] = area > 0 ? (float) nstmNormal / area : 0.0f;
    features[2] = area > 0 ? (float) stmFort / area : 0.0f;
    features[3] = area > 0 ? (float) nstmFort / area : 0.0f;
    features[4] = area > 0 ? (float) neutral / area : 0.0f;
    features[5] = area > 0 ? (float) empty / area : 0.0f;
    features[6] = stmBaseAlive;
    features[7] = nstmBaseAlive;
    features[8] = stmMinDist;
    features[9] = nstmMinDist;
    features[10] = stmCcRatio;
    features[11] = nstmCcRatio;
    features[12] = turnNumberNorm;
    features[13] = boardSizeNorm;

    return features;
  }
}
