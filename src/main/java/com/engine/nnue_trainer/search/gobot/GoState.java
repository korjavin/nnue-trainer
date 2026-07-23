package com.engine.nnue_trainer.search.gobot;

import com.engine.nnue_trainer.board.Action;
import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.board.MoveAction;
import com.engine.nnue_trainer.board.PlaceNeutralsAction;
import com.engine.nnue_trainer.board.Pos;
import java.util.ArrayList;
import java.util.List;

/**
 * Faithful port of GoBot's {@code game.State} ({@code ../virusgame/backend/game/state.go}) as seen
 * by the search — carries the grid plus the non-board hidden state the search and its
 * transposition-table key depend on ({@code currentPlayer}, {@code movesLeft}, per-player {@code
 * active}/{@code neutralUsed}) and implements the rules transitions the search hot-path needs
 * ({@code applyGenerated} / {@code Apply} / connectivity / turn advance / elimination).
 *
 * <p>Value semantics like Go: {@link #applyGenerated}/{@link #apply} copy before mutating, so a
 * parent state stays safe to retain in the search tree. A {@link Cell} slot is always replaced (a
 * new {@code Cell}), never mutated in place, so the shallow {@code cells} clone is safe.
 */
public final class GoState {

  static final int ACTIONS_PER_TURN = 3;

  final int rows;
  final int cols;
  final int players;
  final Cell[] cells; // row-major, length rows*cols
  final Pos[] bases; // length 4, fixed corners
  final boolean[] active; // index player-1, length 4
  final boolean[] neutralUsed; // index player-1, length 4
  int current;
  int movesLeft;
  int winner;
  boolean over;

  private GoState(
      int rows,
      int cols,
      int players,
      Cell[] cells,
      Pos[] bases,
      boolean[] active,
      boolean[] neutralUsed,
      int current,
      int movesLeft,
      int winner,
      boolean over) {
    this.rows = rows;
    this.cols = cols;
    this.players = players;
    this.cells = cells;
    this.bases = bases;
    this.active = active;
    this.neutralUsed = neutralUsed;
    this.current = current;
    this.movesLeft = movesLeft;
    this.winner = winner;
    this.over = over;
  }

  /**
   * Builds a mid-game position from a fixture record's {@code board + player + movesLeft +
   * neutralUsed} — the same hidden state the oracle emits. Our game is 1v1, so {@code players ==
   * 2}; {@code active[p]} is derived from an intact base (GoBot flips the flag only on elimination,
   * which for a non-terminal snapshot coincides with base-intact — same proxy as {@link
   * com.engine.nnue_trainer.search.eval.HandTunedEval}).
   */
  public static GoState fromBoard(
      Board board, int currentPlayer, int movesLeft, boolean[] neutralUsed) {
    int rows = board.rows;
    int cols = board.cols;
    Cell[] cells = new Cell[rows * cols];
    for (int r = 0; r < rows; r++) {
      for (int c = 0; c < cols; c++) {
        Cell cell = board.getCell(r, c);
        cells[r * cols + c] = new Cell(cell.owner, cell.kind);
      }
    }
    Pos[] bases = defaultBases(rows, cols);
    boolean[] active = new boolean[4];
    for (int p = 1; p <= 2; p++) {
      int bi = baseIndex(rows, cols, p);
      active[p - 1] = cells[bi].owner == p && cells[bi].kind == CellKind.BASE;
    }
    boolean[] nu = new boolean[4];
    if (neutralUsed != null) {
      System.arraycopy(neutralUsed, 0, nu, 0, Math.min(neutralUsed.length, 4));
    }
    return new GoState(rows, cols, 2, cells, bases, active, nu, currentPlayer, movesLeft, 0, false);
  }

  private static Pos[] defaultBases(int rows, int cols) {
    return new Pos[] {
      new Pos(0, 0), new Pos(rows - 1, cols - 1), new Pos(0, cols - 1), new Pos(rows - 1, 0)
    };
  }

  /** Base cell corner for a player, matching HandTunedEval / GoBot's {@code bases}. */
  static int baseIndex(int rows, int cols, int player) {
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

  /** Copy-before-mutate helper (Go's {@code next := *s; next.cells = append(...)}). */
  private GoState copy() {
    return new GoState(
        rows,
        cols,
        players,
        cells.clone(),
        bases,
        active.clone(),
        neutralUsed.clone(),
        current,
        movesLeft,
        winner,
        over);
  }

  public int rows() {
    return rows;
  }

  public int cols() {
    return cols;
  }

  public int currentPlayer() {
    return current;
  }

  public int movesLeft() {
    return movesLeft;
  }

  public boolean gameOver() {
    return over;
  }

  public int winner() {
    return winner;
  }

  public boolean active(int player) {
    return player >= 1 && player <= players && active[player - 1];
  }

  public boolean neutralUsed(int player) {
    return player >= 1 && player <= 4 && neutralUsed[player - 1];
  }

  public Cell at(int row, int col) {
    return cells[row * cols + col];
  }

  int index(Pos pos) {
    return pos.row * cols + pos.col;
  }

  boolean inBounds(Pos pos) {
    return pos.row >= 0 && pos.row < rows && pos.col >= 0 && pos.col < cols;
  }

  private void set(Pos pos, Cell cell) {
    cells[index(pos)] = cell;
  }

  // --- rules transitions (port of state.go) ---

  /**
   * Port of GoBot's {@code State.LegalActions}: move targets in stable board order, then every
   * owned-normal neutral pair (board order, i&lt;j) at the start of a turn.
   */
  public List<Action> legalActions() {
    List<Action> actions = new ArrayList<>();
    if (over || !active(current)) {
      return actions;
    }
    for (Pos target : moveTargets(current)) {
      actions.add(new MoveAction(target));
    }
    if (movesLeft == ACTIONS_PER_TURN && !neutralUsed[current - 1]) {
      List<Pos> owned = ownedNormals(current);
      for (int i = 0; i < owned.size(); i++) {
        for (int j = i + 1; j < owned.size(); j++) {
          actions.add(new PlaceNeutralsAction(owned.get(i), owned.get(j)));
        }
      }
    }
    return actions;
  }

  List<Pos> ownedNormals(int player) {
    List<Pos> owned = new ArrayList<>();
    for (int index = 0; index < cells.length; index++) {
      Cell cell = cells[index];
      if (cell.owner == player && cell.kind == CellKind.NORMAL) {
        owned.add(new Pos(index / cols, index % cols));
      }
    }
    return owned;
  }

  /** Port of {@code State.Apply}: legality-checked successor; returns {@code null} if illegal. */
  public GoState apply(Action action) {
    if (over || !legalAction(action)) {
      return null;
    }
    return mutate(action);
  }

  /**
   * Port of {@code State.applyGenerated}: the search hot-path transition for an action already
   * emitted by {@link GoPosition}. Skips the legality traversal but shares the mutation,
   * elimination, terminal, and turn-advance semantics with {@link #apply}.
   */
  GoState applyGenerated(Action action) {
    return mutate(action);
  }

  private GoState mutate(Action action) {
    GoState next = copy();
    int player = current;
    if (action instanceof PlaceNeutralsAction) {
      PlaceNeutralsAction pn = (PlaceNeutralsAction) action;
      next.set(pn.pos1, new Cell(0, CellKind.NEUTRAL));
      next.set(pn.pos2, new Cell(0, CellKind.NEUTRAL));
      next.neutralUsed[player - 1] = true;
      next.movesLeft = 0;
    } else {
      Pos target = ((MoveAction) action).target;
      Cell targetCell = cells[index(target)];
      CellKind kind = targetCell.kind == CellKind.NORMAL ? CellKind.FORTIFIED : CellKind.NORMAL;
      next.set(target, new Cell(player, kind));
      next.movesLeft--;
    }
    next.eliminateStuckPlayers();
    if (next.finishIfTerminal()) {
      return next;
    }
    if (!next.active(player) || next.movesLeft == 0) {
      next.advance(player);
    }
    return next;
  }

  private boolean legalAction(Action action) {
    if (!active(current)) {
      return false;
    }
    if (action instanceof MoveAction) {
      return legalMove(current, ((MoveAction) action).target);
    }
    if (action instanceof PlaceNeutralsAction) {
      PlaceNeutralsAction pn = (PlaceNeutralsAction) action;
      if (movesLeft != ACTIONS_PER_TURN || neutralUsed[current - 1] || pn.pos1.equals(pn.pos2)) {
        return false;
      }
      for (Pos pos : new Pos[] {pn.pos1, pn.pos2}) {
        if (!inBounds(pos)) {
          return false;
        }
        Cell cell = cells[index(pos)];
        if (cell.owner != current || cell.kind != CellKind.NORMAL) {
          return false;
        }
      }
      return true;
    }
    return false;
  }

  private boolean legalMove(int player, Pos target) {
    if (!inBounds(target)) {
      return false;
    }
    Cell cell = cells[index(target)];
    if (cell.kind != CellKind.EMPTY && (cell.kind != CellKind.NORMAL || cell.owner == player)) {
      return false;
    }
    boolean[] connected = connected(player);
    for (int row = target.row - 1; row <= target.row + 1; row++) {
      for (int col = target.col - 1; col <= target.col + 1; col++) {
        Pos pos = new Pos(row, col);
        if (!pos.equals(target) && inBounds(pos) && connected[index(pos)]) {
          return true;
        }
      }
    }
    return false;
  }

  boolean[] connected(int player) {
    boolean[] seen = new boolean[cells.length];
    if (player < 1 || player > players) {
      return seen;
    }
    Pos base = bases[player - 1];
    Cell cell = cells[index(base)];
    if (cell.owner != player || cell.kind != CellKind.BASE) {
      return seen;
    }
    int[] queue = new int[cells.length];
    int baseIdx = index(base);
    seen[baseIdx] = true;
    queue[0] = baseIdx;
    int head = 0;
    int tail = 1;
    while (head < tail) {
      int cur = queue[head++];
      int r = cur / cols;
      int c = cur % cols;
      for (int nr = r - 1; nr <= r + 1; nr++) {
        for (int nc = c - 1; nc <= c + 1; nc++) {
          if ((nr == r && nc == c) || nr < 0 || nr >= rows || nc < 0 || nc >= cols) {
            continue;
          }
          int ni = nr * cols + nc;
          if (!seen[ni] && cells[ni].owner == player) {
            seen[ni] = true;
            queue[tail++] = ni;
          }
        }
      }
    }
    return seen;
  }

  List<Pos> moveTargets(int player) {
    return moveTargetsFrom(player, connected(player));
  }

  List<Pos> moveTargetsFrom(int player, boolean[] connected) {
    boolean[] frontier = new boolean[cells.length];
    for (int index = 0; index < connected.length; index++) {
      if (!connected[index]) {
        continue;
      }
      int r = index / cols;
      int c = index % cols;
      for (int nr = r - 1; nr <= r + 1; nr++) {
        for (int nc = c - 1; nc <= c + 1; nc++) {
          if (nr < 0 || nr >= rows || nc < 0 || nc >= cols) {
            continue;
          }
          int ni = nr * cols + nc;
          Cell cell = cells[ni];
          if (cell.kind == CellKind.EMPTY
              || (cell.kind == CellKind.NORMAL && cell.owner != player)) {
            frontier[ni] = true;
          }
        }
      }
    }
    List<Pos> targets = new ArrayList<>();
    for (int index = 0; index < frontier.length; index++) {
      if (frontier[index]) {
        targets.add(new Pos(index / cols, index % cols));
      }
    }
    return targets;
  }

  private void eliminateStuckPlayers() {
    for (int player = 1; player <= players; player++) {
      if (active(player) && !hasMove(player)) {
        // GoBot vs-ai2.45: eliminated players' cells stay on the board; only the flag flips.
        active[player - 1] = false;
      }
    }
  }

  private boolean hasMove(int player) {
    boolean[] connected = connected(player);
    for (int index = 0; index < connected.length; index++) {
      if (!connected[index]) {
        continue;
      }
      int r = index / cols;
      int c = index % cols;
      for (int nr = r - 1; nr <= r + 1; nr++) {
        for (int nc = c - 1; nc <= c + 1; nc++) {
          if (nr < 0 || nr >= rows || nc < 0 || nc >= cols) {
            continue;
          }
          Cell cell = cells[nr * cols + nc];
          if (cell.kind == CellKind.EMPTY
              || (cell.kind == CellKind.NORMAL && cell.owner != player)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  private boolean finishIfTerminal() {
    int activePlayers = 0;
    int win = 0;
    for (int player = 1; player <= players; player++) {
      if (active(player)) {
        activePlayers++;
        win = player;
      }
    }
    if (activePlayers > 1) {
      return false;
    }
    over = true;
    winner = win;
    movesLeft = 0;
    return true;
  }

  private void advance(int after) {
    for (int offset = 1; offset <= players; offset++) {
      int player = (after - 1 + offset) % players + 1;
      if (active(player)) {
        current = player;
        movesLeft = ACTIONS_PER_TURN;
        return;
      }
    }
  }

  /**
   * FNV-1a position hash, byte-for-byte identical to GoBot's {@code stateHash} ({@code search.go}).
   * The TT is keyed by this, so it MUST match GoBot exactly (a divergence here silently breaks
   * parity). Java {@code long} multiplication wraps mod 2^64 like Go's {@code uint64}.
   */
  public long hash() {
    final long prime = 1099511628211L;
    long hash = 0xcbf29ce484222325L; // 1469598103934665603
    hash = add(hash, prime, rows);
    hash = add(hash, prime, cols);
    hash = add(hash, prime, current);
    hash = add(hash, prime, movesLeft);
    for (int player = 1; player <= 4; player++) {
      if (active(player)) {
        hash = add(hash, prime, player | 0x10);
      }
      if (neutralUsed(player)) {
        hash = add(hash, prime, player | 0x20);
      }
    }
    for (int row = 0; row < rows; row++) {
      for (int col = 0; col < cols; col++) {
        Cell cell = cells[row * cols + col];
        hash = add(hash, prime, (cell.owner << 3) | cell.kind.value);
      }
    }
    return hash;
  }

  private static long add(long hash, long prime, int value) {
    hash ^= (value & 0xffL);
    hash *= prime;
    return hash;
  }
}
