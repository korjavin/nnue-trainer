package com.engine.nnue_trainer.search.eval;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;

/**
 * Faithful integer-exact port of GoBot's hand-tuned static evaluation ({@code
 * ../virusgame/backend/search/evaluate.go}, {@code StaticEval}).
 *
 * <p>Higher = better for {@code player}. This is a literal translation of the Go source (same
 * structure, same operation order) so that scores are integer-identical to GoBot on the parity
 * fixture. Do NOT "improve" the math — any divergence is a bug.
 *
 * <p>The score depends on non-board state that is not reconstructable from the grid: the mover
 * ({@code player} == {@code state.CurrentPlayer()}), {@code movesLeft} and per-player {@code
 * neutralUsed}. Those must be supplied by the caller.
 */
public final class HandTunedEval {

  // Kind codes match GoBot's game.CellKind iota AND Java CellKind.value.
  private static final int EMPTY = CellKind.EMPTY.value; // 0
  private static final int NORMAL = CellKind.NORMAL.value; // 1
  private static final int BASE = CellKind.BASE.value; // 2
  private static final int FORTIFIED = CellKind.FORTIFIED.value; // 3

  private static final int MATE_SCORE = 1_000_000_000;
  private static final int SPACE_RACE_WEIGHT = 32;

  // defaultEvalParams() literals.
  private static final int W_CONNECTED = 10;
  private static final int W_NORMAL = 30;
  private static final int W_FORTIFIED = 6;
  private static final int W_MOBILITY = 1;
  private static final int W_CAPTURES = 1;
  private static final int W_DISCONNECTED = 1;
  private static final int W_BASE_EXITS = 180;
  private static final int W_BASE_OPENINGS = 80;
  private static final int W_BASE_ANCHORS = 240;
  private static final int W_BASE_THREAT = 650;
  private static final int W_THREATENED_LOSS_MULT = 1;
  private static final int W_THREATENED_MULT = 1;
  private static final int W_SPACE_RACE = SPACE_RACE_WEIGHT;
  private static final int W_SEALED_BASE_PENALTY = 5000;
  private static final int W_NEUTRAL_UNUSED_BONUS = 20;
  private static final int W_MOVES_LEFT_TEMPO = 12;
  private static final int W_PREDATORY_CUT_BASE = 150;
  private static final int W_PREDATORY_CUT_LOSS_DIV = 2;

  private HandTunedEval() {}

  /** Per-player analysis metrics (mirror of Go's playerMetrics). */
  private static final class Metrics {
    int connected, disconnected;
    int normal, fortified;
    int mobility, captures;
    int baseExits, baseOpenings;
    int baseAnchors, baseThreat;
    int threatened, threatenedLoss;
    int threatTempo;
    boolean[] articulation;
    int[] cutLoss;
    boolean[] connectedCells;
  }

  /**
   * Static evaluation for {@code player} (1-based), integer-identical to GoBot.
   *
   * <p>Parity path: the fixture records set {@code player == CurrentPlayer()}, so the utility index
   * and the tempo mover coincide. Search must instead call the 5-arg overload, which keeps them
   * separate (GoBot evaluates {@code s.root} but reads {@code state.CurrentPlayer()} for tempo).
   *
   * @param board the position
   * @param player the mover (== GoBot's CurrentPlayer)
   * @param movesLeft actions remaining this turn (0..3)
   * @param neutralUsed per-player neutral-used flag, index 0 == player 1
   */
  public static int staticEval(Board board, int player, int movesLeft, boolean[] neutralUsed) {
    return staticEval(board, player, player, movesLeft, neutralUsed);
  }

  /**
   * Static evaluation for {@code scorePlayer} (1-based), integer-identical to GoBot.
   *
   * <p>GoBot keeps two roles separate: the utility index ({@code s.root} in search) and the mover
   * read for the tempo terms ({@code state.CurrentPlayer()}). At an opponent-to-move leaf these
   * differ, so search passes the leaf's side-to-move as {@code currentPlayer} while still scoring
   * from the root's frame.
   *
   * @param board the position
   * @param scorePlayer whose utility to return (== GoBot's s.root)
   * @param currentPlayer the mover, for the tempo terms (== GoBot's CurrentPlayer())
   * @param movesLeft actions remaining this turn (0..3)
   * @param neutralUsed per-player neutral-used flag, index 0 == player 1
   */
  public static int staticEval(
      Board board, int scorePlayer, int currentPlayer, int movesLeft, boolean[] neutralUsed) {
    int rows = board.rows;
    int cols = board.cols;
    int size = rows * cols;

    // Snapshot cells into flat arrays (row*cols+col).
    int[] owner = new int[size];
    int[] kind = new int[size];
    for (int r = 0; r < rows; r++) {
      for (int c = 0; c < cols; c++) {
        Cell cell = board.getCell(r, c);
        int idx = r * cols + c;
        owner[idx] = cell.owner;
        kind[idx] = cell.kind.value;
      }
    }

    boolean[] active = new boolean[4];
    boolean[][] connected = new boolean[4][];
    for (int p = 1; p <= 4; p++) {
      active[p - 1] = isActive(owner, kind, rows, cols, p);
      connected[p - 1] = new boolean[size];
      if (active[p - 1]) {
        connectedCellsInto(owner, kind, rows, cols, p, connected[p - 1]);
      }
    }

    int[] space = spaceRace(owner, kind, rows, cols, connected);

    Metrics[] metrics = new Metrics[4];
    int[] raw = new int[4];
    int activeCount = 0;
    for (int p = 1; p <= 4; p++) {
      if (!active[p - 1]) {
        raw[p - 1] = -MATE_SCORE / 2;
        continue;
      }
      activeCount++;
      Metrics m = analyze(owner, kind, rows, cols, p, active, connected, currentPlayer, movesLeft);
      metrics[p - 1] = m;
      int area = rows * cols;
      int owned = m.normal + m.fortified + 1; // include the base
      int r =
          normalized(m.connected, area, W_CONNECTED)
              + normalized(m.normal, area, W_NORMAL)
              + normalized(m.fortified, area, W_FORTIFIED)
              + normalized(m.mobility, area, W_MOBILITY)
              + normalized(m.captures, area, W_CAPTURES)
              - normalized(m.disconnected, owned, W_DISCONNECTED)
              + W_BASE_EXITS * m.baseExits
              + W_BASE_OPENINGS * m.baseOpenings
              + W_BASE_ANCHORS * m.baseAnchors
              - W_BASE_THREAT * m.baseThreat * m.threatTempo
              - m.threatTempo
                  * W_THREATENED_LOSS_MULT
                  * ratio(m.threatenedLoss, Math.max(1, m.connected))
              - m.threatTempo * W_THREATENED_MULT * ratio(m.threatened, Math.max(1, m.connected))
              + normalized(space[p - 1], area, W_SPACE_RACE);
      if (m.baseExits + m.baseOpenings == 0) {
        r -= W_SEALED_BASE_PENALTY;
      }
      if (!neutralUsedFor(neutralUsed, p)) {
        r += W_NEUTRAL_UNUSED_BONUS;
      }
      if (currentPlayer == p) {
        r += movesLeft * W_MOVES_LEFT_TEMPO;
      }
      raw[p - 1] = r;
    }

    // Predatory-cut bonus: reward standing adjacent to an opponent's articulation cell.
    for (int p = 1; p <= 4; p++) {
      if (!active[p - 1]) {
        continue;
      }
      Metrics own = metrics[p - 1];
      for (int opp = 1; opp <= 4; opp++) {
        if (opp == p || !active[opp - 1]) {
          continue;
        }
        Metrics oppM = metrics[opp - 1];
        for (int index = 0; index < oppM.articulation.length; index++) {
          if (oppM.articulation[index]
              && adjacentConnected(index, rows, cols, own.connectedCells)) {
            int loss = oppM.cutLoss[index];
            raw[p - 1] +=
                W_PREDATORY_CUT_BASE
                    + ratio(loss, Math.max(1, oppM.connected)) / W_PREDATORY_CUT_LOSS_DIV;
          }
        }
      }
    }

    // Utility: raw minus average of active opponents' raw.
    int[] utility = new int[4];
    for (int p = 1; p <= 4; p++) {
      if (!active[p - 1]) {
        utility[p - 1] = raw[p - 1];
        continue;
      }
      int opponents = 0;
      for (int other = 1; other <= 4; other++) {
        if (other != p && active[other - 1]) {
          opponents += raw[other - 1];
        }
      }
      if (activeCount > 1) {
        utility[p - 1] = raw[p - 1] - opponents / (activeCount - 1);
      } else {
        utility[p - 1] = raw[p - 1];
      }
    }
    return utility[scorePlayer - 1];
  }

  // --- helpers, mirroring evaluate.go ---

  /**
   * A player is active iff its base cell (fixed corner) is intact. GoBot flags a player inactive
   * only when its base is captured, which ends a 2-player game; non-terminal fixture records
   * therefore have an intact base exactly for the active players.
   */
  private static boolean isActive(int[] owner, int[] kind, int rows, int cols, int player) {
    int bi = baseIndex(rows, cols, player);
    return owner[bi] == player && kind[bi] == BASE;
  }

  private static boolean neutralUsedFor(boolean[] neutralUsed, int player) {
    return neutralUsed != null && player - 1 < neutralUsed.length && neutralUsed[player - 1];
  }

  private static int baseIndex(int rows, int cols, int player) {
    switch (player) {
      case 1:
        return 0; // (0,0)
      case 2:
        return (rows - 1) * cols + (cols - 1); // (rows-1, cols-1)
      case 3:
        return cols - 1; // (0, cols-1)
      default:
        return (rows - 1) * cols; // (rows-1, 0)
    }
  }

  /** Neighbor indices (8-connected, in bounds); returns count, fills into {@code out}. */
  private static int neighbors(int index, int rows, int cols, int[] out) {
    int row = index / cols;
    int col = index % cols;
    int count = 0;
    for (int r = row - 1; r <= row + 1; r++) {
      for (int c = col - 1; c <= col + 1; c++) {
        if (r >= 0 && r < rows && c >= 0 && c < cols && (r != row || c != col)) {
          out[count++] = r * cols + c;
        }
      }
    }
    return count;
  }

  private static void connectedCellsInto(
      int[] owner, int[] kind, int rows, int cols, int player, boolean[] seen) {
    int bi = baseIndex(rows, cols, player);
    if (owner[bi] != player || kind[bi] != BASE) {
      return;
    }
    int[] queue = new int[rows * cols];
    int[] nb = new int[8];
    seen[bi] = true;
    queue[0] = bi;
    int head = 0;
    int tail = 1;
    while (head < tail) {
      int index = queue[head++];
      int count = neighbors(index, rows, cols, nb);
      for (int i = 0; i < count; i++) {
        int ni = nb[i];
        if (!seen[ni] && owner[ni] == player) {
          seen[ni] = true;
          queue[tail++] = ni;
        }
      }
    }
  }

  private static int[] spaceRace(
      int[] owner, int[] kind, int rows, int cols, boolean[][] connected) {
    int size = rows * cols;
    int[] dist = new int[size];
    int[] cellOwner = new int[size]; // -1 unset, -2 contested, else player index
    int[] queue = new int[size];
    int[] nb = new int[8];
    for (int i = 0; i < size; i++) {
      dist[i] = -1;
      cellOwner[i] = -1;
    }
    int tail = 0;
    for (int p = 0; p < 4; p++) {
      if (connected[p] == null) {
        continue;
      }
      for (int i = 0; i < size; i++) {
        if (connected[p][i]) {
          dist[i] = 0;
          cellOwner[i] = p;
          queue[tail++] = i;
        }
      }
    }
    for (int head = 0; head < tail; head++) {
      int idx = queue[head];
      int d = dist[idx];
      int o = cellOwner[idx];
      int count = neighbors(idx, rows, cols, nb);
      for (int i = 0; i < count; i++) {
        int ni = nb[i];
        if (kind[ni] != EMPTY) {
          continue;
        }
        if (dist[ni] == -1) {
          dist[ni] = d + 1;
          cellOwner[ni] = o;
          queue[tail++] = ni;
        } else if (dist[ni] == d + 1 && cellOwner[ni] != o && cellOwner[ni] != -2) {
          cellOwner[ni] = -2; // contested
        }
      }
    }
    int[] counts = new int[4];
    for (int i = 0; i < size; i++) {
      if (kind[i] == EMPTY && cellOwner[i] >= 0) {
        counts[cellOwner[i]]++;
      }
    }
    return counts;
  }

  private static Metrics analyze(
      int[] owner,
      int[] kind,
      int rows,
      int cols,
      int player,
      boolean[] active,
      boolean[][] connected,
      int current,
      int movesLeft) {
    int size = rows * cols;
    Metrics m = new Metrics();
    m.connectedCells = connected[player - 1];
    m.articulation = new boolean[size];
    m.cutLoss = new int[size];
    articulationPointsInto(
        owner, kind, rows, cols, player, m.connectedCells, m.articulation, m.cutLoss);

    boolean[] targets = new boolean[size];
    int[] nb = new int[8];
    for (int index = 0; index < size; index++) {
      int cellOwner = owner[index];
      int cellKind = kind[index];
      if (cellOwner == player) {
        if (cellKind == NORMAL) {
          m.normal++;
        } else if (cellKind == FORTIFIED) {
          m.fortified++;
        }
        if (m.connectedCells[index]) {
          m.connected++;
        } else {
          m.disconnected++;
        }
      }
      if (m.connectedCells[index]
          && cellKind == NORMAL
          && threatenedByConnected(index, rows, cols, player, active, connected)) {
        m.threatened++;
        if (m.articulation[index]) {
          m.threatenedLoss += m.cutLoss[index];
        }
      }
      if (!m.connectedCells[index]) {
        continue;
      }
      int count = neighbors(index, rows, cols, nb);
      for (int i = 0; i < count; i++) {
        int ti = nb[i];
        int tk = kind[ti];
        if (!targets[ti] && (tk == EMPTY || (tk == NORMAL && owner[ti] != player))) {
          targets[ti] = true;
          m.mobility++;
          if (tk == NORMAL) {
            m.captures++;
          }
        }
      }
    }

    int bi = baseIndex(rows, cols, player);
    int count = neighbors(bi, rows, cols, nb);
    for (int i = 0; i < count; i++) {
      int index = nb[i];
      int cellOwner = owner[index];
      int cellKind = kind[index];
      if (cellOwner == player && m.connectedCells[index]) {
        m.baseExits++;
        if (cellKind == FORTIFIED) {
          m.baseAnchors++;
        }
      } else if (cellKind == EMPTY) {
        m.baseOpenings++;
      } else if (cellKind == NORMAL && cellOwner != player) {
        m.baseOpenings++;
        if (threatenedByConnected(bi, rows, cols, player, active, connected)) {
          m.baseThreat++;
        }
      }
    }
    m.threatTempo = threatTempo(player, current, movesLeft);
    return m;
  }

  /**
   * More urgent as the defender spends its turn, fully urgent while an opponent still has actions.
   * {@code current} is the mover (GoBot's CurrentPlayer).
   */
  private static int threatTempo(int player, int current, int movesLeft) {
    if (current == player) {
      return Math.max(1, 4 - movesLeft);
    }
    return Math.max(1, movesLeft);
  }

  // --- articulation points (Tarjan), mirror of articulationPointsInto ---

  private static void articulationPointsInto(
      int[] owner,
      int[] kind,
      int rows,
      int cols,
      int player,
      boolean[] connected,
      boolean[] result,
      int[] cutLoss) {
    int size = connected.length;
    int[] discovery = new int[size];
    int[] low = new int[size];
    int[] parent = new int[size];
    int[] subtree = new int[size];
    for (int i = 0; i < size; i++) {
      parent[i] = -1;
    }
    int[] timeBox = {0};

    int bi = baseIndex(rows, cols, player);
    if (bi >= 0 && bi < size && connected[bi]) {
      visit(bi, rows, cols, connected, discovery, low, parent, subtree, result, cutLoss, timeBox);
    }
    for (int index = 0; index < size; index++) {
      if (connected[index] && discovery[index] == 0) {
        visit(
            index, rows, cols, connected, discovery, low, parent, subtree, result, cutLoss,
            timeBox);
      }
    }
    for (int index = 0; index < size; index++) {
      if (result[index]) {
        if (kind[index] != NORMAL || owner[index] != player) {
          result[index] = false;
          cutLoss[index] = 0;
        } else {
          cutLoss[index]++;
        }
      }
    }
  }

  private static void visit(
      int index,
      int rows,
      int cols,
      boolean[] connected,
      int[] discovery,
      int[] low,
      int[] parent,
      int[] subtree,
      boolean[] result,
      int[] cutLoss,
      int[] timeBox) {
    timeBox[0]++;
    discovery[index] = timeBox[0];
    low[index] = timeBox[0];
    subtree[index] = 1;
    int children = 0;
    int[] nb = new int[8];
    int count = neighbors(index, rows, cols, nb);
    for (int i = 0; i < count; i++) {
      int nextIndex = nb[i];
      if (!connected[nextIndex]) {
        continue;
      }
      if (discovery[nextIndex] == 0) {
        children++;
        parent[nextIndex] = index;
        visit(
            nextIndex, rows, cols, connected, discovery, low, parent, subtree, result, cutLoss,
            timeBox);
        subtree[index] += subtree[nextIndex];
        if (low[nextIndex] < low[index]) {
          low[index] = low[nextIndex];
        }
        if ((parent[index] == -1 && children > 1)
            || (parent[index] != -1 && low[nextIndex] >= discovery[index])) {
          result[index] = true;
          cutLoss[index] += subtree[nextIndex];
        }
      } else if (nextIndex != parent[index] && discovery[nextIndex] < low[index]) {
        low[index] = discovery[nextIndex];
      }
    }
  }

  private static boolean threatenedByConnected(
      int index, int rows, int cols, int player, boolean[] active, boolean[][] connected) {
    int[] nb = new int[8];
    for (int opp = 1; opp <= 4; opp++) {
      if (opp == player || !active[opp - 1]) {
        continue;
      }
      boolean[] oppConnected = connected[opp - 1];
      if (oppConnected == null) {
        continue;
      }
      int count = neighbors(index, rows, cols, nb);
      for (int i = 0; i < count; i++) {
        if (oppConnected[nb[i]]) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean adjacentConnected(int index, int rows, int cols, boolean[] connected) {
    int[] nb = new int[8];
    int count = neighbors(index, rows, cols, nb);
    for (int i = 0; i < count; i++) {
      if (connected[nb[i]]) {
        return true;
      }
    }
    return false;
  }

  private static int ratio(int value, int denominator) {
    if (value <= 0 || denominator <= 0) {
      return 0;
    }
    return value * 1000 / denominator;
  }

  private static int normalized(int value, int denominator, int weight) {
    if (value <= 0 || denominator <= 0 || weight <= 0) {
      return 0;
    }
    return value * weight * 1000 / denominator;
  }
}
