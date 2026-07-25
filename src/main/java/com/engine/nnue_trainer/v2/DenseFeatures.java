package com.engine.nnue_trainer.v2;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.board.Pos;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;

public class DenseFeatures {

  public static float[] extract(Board board, int activePlayer, int turnNumber) {
    int opponent = 3 - activePlayer;

    int stmNormal = 0;
    int nstmNormal = 0;
    int stmFortified = 0;
    int nstmFortified = 0;
    int neutral = 0;
    int empty = 0;

    float stmBaseAlive = 0.0f;
    float nstmBaseAlive = 0.0f;

    List<Pos> stmCells = new ArrayList<>();
    List<Pos> nstmCells = new ArrayList<>();

    for (int r = 0; r < board.rows; r++) {
      for (int c = 0; c < board.cols; c++) {
        Cell cell = board.getCell(r, c);
        if (cell == null || cell.kind == CellKind.EMPTY) {
          empty++;
        } else if (cell.kind == CellKind.NEUTRAL) {
          neutral++;
        } else if (cell.kind == CellKind.NORMAL) {
          if (cell.owner == activePlayer) {
            stmNormal++;
            stmCells.add(new Pos(r, c));
          } else if (cell.owner == opponent) {
            nstmNormal++;
            nstmCells.add(new Pos(r, c));
          }
        } else if (cell.kind == CellKind.FORTIFIED) {
          if (cell.owner == activePlayer) {
            stmFortified++;
            stmCells.add(new Pos(r, c));
          } else if (cell.owner == opponent) {
            nstmFortified++;
            nstmCells.add(new Pos(r, c));
          }
        } else if (cell.kind == CellKind.BASE) {
          if (cell.owner == activePlayer) {
            stmBaseAlive = 1.0f;
            stmCells.add(new Pos(r, c));
          } else if (cell.owner == opponent) {
            nstmBaseAlive = 1.0f;
            nstmCells.add(new Pos(r, c));
          }
        }
      }
    }

    float maxDist = board.rows + board.cols - 2.0f;
    float totalArea = board.rows * board.cols;

    float stmMinDist = maxDist;
    float nstmMinDist = maxDist;

    Pos nstmBasePos = getBasePos(opponent, board.rows, board.cols);
    if (nstmBasePos != null) {
      for (Pos p : stmCells) {
        float d = manhattanDist(p.row, p.col, nstmBasePos.row, nstmBasePos.col);
        if (d < stmMinDist) {
          stmMinDist = d;
        }
      }
    }

    Pos stmBasePos = getBasePos(activePlayer, board.rows, board.cols);
    if (stmBasePos != null) {
      for (Pos p : nstmCells) {
        float d = manhattanDist(p.row, p.col, stmBasePos.row, stmBasePos.col);
        if (d < nstmMinDist) {
          nstmMinDist = d;
        }
      }
    }

    float stmComponents = getComponents(stmCells);
    float nstmComponents = getComponents(nstmCells);

    float stmCompRatio = stmCells.isEmpty() ? 0.0f : stmComponents / stmCells.size();
    float nstmCompRatio = nstmCells.isEmpty() ? 0.0f : nstmComponents / nstmCells.size();

    float[] features = new float[14];
    features[0] = stmNormal / totalArea;
    features[1] = nstmNormal / totalArea;
    features[2] = stmFortified / totalArea;
    features[3] = nstmFortified / totalArea;
    features[4] = neutral / totalArea;
    features[5] = empty / totalArea;
    features[6] = stmBaseAlive;
    features[7] = nstmBaseAlive;
    features[8] = stmMinDist / maxDist;
    features[9] = nstmMinDist / maxDist;
    features[10] = stmCompRatio;
    features[11] = nstmCompRatio;
    features[12] = turnNumber / 100.0f;
    features[13] = totalArea / 144.0f;

    return features;
  }

  private static Pos getBasePos(int player, int rows, int cols) {
    if (player == 1) return new Pos(0, 0);
    if (player == 2) return new Pos(rows - 1, cols - 1);
    if (player == 3) return new Pos(0, cols - 1);
    if (player == 4) return new Pos(rows - 1, 0);
    return null;
  }

  private static int manhattanDist(int r1, int c1, int r2, int c2) {
    return Math.abs(r1 - r2) + Math.abs(c1 - c2);
  }

  private static int getComponents(List<Pos> cells) {
    if (cells.isEmpty()) return 0;

    Set<Pos> visited = new HashSet<>();
    Set<Pos> cellSet = new HashSet<>(cells);
    int components = 0;

    int[] dr = {-1, -1, -1, 0, 0, 1, 1, 1};
    int[] dc = {-1, 0, 1, -1, 1, -1, 0, 1};

    for (Pos startCell : cells) {
      if (!visited.contains(startCell)) {
        components++;
        Queue<Pos> queue = new LinkedList<>();
        queue.add(startCell);
        visited.add(startCell);

        while (!queue.isEmpty()) {
          Pos curr = queue.poll();
          for (int i = 0; i < 8; i++) {
            Pos neighbor = new Pos(curr.row + dr[i], curr.col + dc[i]);
            if (cellSet.contains(neighbor) && !visited.contains(neighbor)) {
              visited.add(neighbor);
              queue.add(neighbor);
            }
          }
        }
      }
    }

    return components;
  }
}
