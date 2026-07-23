package com.engine.nnue_trainer.search.gobot;

import com.engine.nnue_trainer.board.Action;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.board.MoveAction;
import com.engine.nnue_trainer.board.PlaceNeutralsAction;
import com.engine.nnue_trainer.board.Pos;
import java.util.ArrayList;
import java.util.List;

/**
 * Faithful port of GoBot's {@code game.Position} ({@code ../virusgame/backend/game/position.go}):
 * the search-facing move enumerator. {@link #searchActions()} reproduces {@code
 * ForEachSearchAction} exactly — moves in stable board order first, then either every neutral pair
 * (small positions, kept exact for deterministic tie-breaking) or the bounded {@code
 * strategicNeutralPairs} defensive set above the branch threshold. Move order here IS the search
 * move order, so it must match GoBot byte-for-byte.
 */
final class GoPosition {

  // Branch-count ceiling at/below which every neutral pair is enumerated exactly.
  private static final int EXACT_BRANCH_LIMIT = 32;
  private static final int MAX_STRATEGIC_PAIRS = 48;

  private final GoState state;
  private final List<Pos> moves;
  private final List<Pos> owned;
  private final List<int[]> searchPairs; // ordered-index pairs, or null below the threshold

  private GoPosition(GoState state, List<Pos> moves, List<Pos> owned, List<int[]> searchPairs) {
    this.state = state;
    this.moves = moves;
    this.owned = owned;
    this.searchPairs = searchPairs;
  }

  /** Port of {@code NewPosition}: compute the mover's connectivity once and share it. */
  static GoPosition of(GoState state) {
    boolean[] connected = state.connected(state.current);
    List<Pos> moves = state.moveTargetsFrom(state.current, connected);
    List<Pos> owned = null;
    List<int[]> searchPairs = null;
    if (canPlaceNeutrals(state)) {
      owned = state.ownedNormals(state.current);
      if (usesStrategicPairs(moves.size(), owned.size())) {
        searchPairs = strategicNeutralPairs(state, owned, connected);
      }
    }
    return new GoPosition(state, moves, owned, searchPairs);
  }

  GoState state() {
    return state;
  }

  /**
   * Successor for an action already emitted by {@link #searchActions} (GoBot's {@code
   * ApplySearch}).
   */
  GoState applySearch(Action action) {
    return state.applyGenerated(action);
  }

  private static boolean canPlaceNeutrals(GoState s) {
    return s.movesLeft == GoState.ACTIONS_PER_TURN && !s.neutralUsed[s.current - 1];
  }

  private static boolean usesStrategicPairs(int moves, int owned) {
    return moves + owned * (owned - 1) / 2 > EXACT_BRANCH_LIMIT;
  }

  /** Port of {@code ForEachSearchAction}, materialized in enumeration order. */
  List<Action> searchActions() {
    List<Action> actions = new ArrayList<>();
    if (state.over || !state.active(state.current)) {
      return actions;
    }
    for (Pos target : moves) {
      actions.add(new MoveAction(target));
    }
    if (!canPlaceNeutrals(state)) {
      return actions;
    }
    if (!usesStrategicPairs(moves.size(), owned.size())) {
      for (int i = 0; i < owned.size(); i++) {
        for (int j = i + 1; j < owned.size(); j++) {
          actions.add(new PlaceNeutralsAction(owned.get(i), owned.get(j)));
        }
      }
      return actions;
    }
    for (int[] pair : searchPairs) {
      actions.add(new PlaceNeutralsAction(posOf(state, pair[0]), posOf(state, pair[1])));
    }
    return actions;
  }

  private static Pos posOf(GoState s, int index) {
    return new Pos(index / s.cols, index % s.cols);
  }

  // --- strategicNeutralPairs (port of position.go) ---

  private static List<int[]> strategicNeutralPairs(
      GoState s, List<Pos> owned, boolean[] connected) {
    int player = s.current;
    int size = s.cells.length;
    ArticulationScratch scratch = new ArticulationScratch(size);
    boolean[] cuts = articulationCells(s, connected, -1, scratch).clone();

    boolean[] threatened = new boolean[size];
    for (int opponent = 1; opponent <= s.players; opponent++) {
      if (opponent == player || !s.active(opponent)) {
        continue;
      }
      for (Pos target : s.moveTargets(opponent)) {
        Cell cell = s.cells[s.index(target)];
        if (cell.owner == player && cell.kind == CellKind.NORMAL) {
          threatened[s.index(target)] = true;
        }
      }
    }

    Pos base = s.bases[player - 1];
    List<Pos> baseDefense = new ArrayList<>();
    List<Pos> threatDefense = new ArrayList<>();
    List<Pos> cutDefense = new ArrayList<>();
    for (Pos pos : owned) {
      if (adjacent(pos, base)) {
        baseDefense.add(pos);
      }
      if (threatened[s.index(pos)]) {
        threatDefense.add(pos);
      }
      if (cuts[s.index(pos)]) {
        cutDefense.add(pos);
      }
    }

    List<Pos> defensive = new ArrayList<>();
    List<List<Pos>> classes = List.of(baseDefense, threatDefense, cutDefense);
    // Seed every available defensive class, then fill in survival priority.
    for (List<Pos> cls : classes) {
      if (!cls.isEmpty()) {
        addDefensive(defensive, cls.get(0));
      }
    }
    for (List<Pos> cls : classes) {
      for (Pos pos : cls) {
        addDefensive(defensive, pos);
      }
    }

    List<Pos> fillers = robustFillers(s, owned, cuts, threatened, defensive, 2);

    List<int[]> defensiveFiller = new ArrayList<>();
    for (Pos cell : defensive) {
      for (Pos filler : fillers) {
        int[] pair = normalize(s, cell, filler);
        if (pair != null) {
          appendUnique(defensiveFiller, pair, MAX_STRATEGIC_PAIRS);
        }
      }
    }
    List<int[]> defensivePairs = new ArrayList<>();
    for (int i = 0; i < defensive.size(); i++) {
      for (int j = i + 1; j < defensive.size(); j++) {
        int[] pair = normalize(s, defensive.get(i), defensive.get(j));
        if (pair != null) {
          appendUnique(defensivePairs, pair, MAX_STRATEGIC_PAIRS);
        }
      }
    }
    List<int[]> separators = new ArrayList<>();
    for (Pos u : defensive) {
      int uIndex = s.index(u);
      if (cuts[uIndex]) {
        continue;
      }
      boolean[] partners = articulationCells(s, connected, uIndex, scratch);
      for (Pos v : owned) {
        if (partners[s.index(v)]) {
          int[] pair = normalize(s, u, v);
          if (pair != null) {
            appendUnique(separators, pair, MAX_STRATEGIC_PAIRS);
          }
        }
      }
    }

    List<int[]> pairs = new ArrayList<>();
    // Reserve one true separator and one pair per defensive cell before distributing.
    if (!separators.isEmpty()) {
      appendUnique(pairs, separators.get(0), MAX_STRATEGIC_PAIRS);
    }
    for (int i = 0; i < defensive.size(); i++) {
      if (!fillers.isEmpty()) {
        int[] pair = normalize(s, defensive.get(i), fillers.get(i % fillers.size()));
        if (pair != null) {
          appendUnique(pairs, pair, MAX_STRATEGIC_PAIRS);
        }
      } else if (defensive.size() > 1) {
        int[] pair = normalize(s, defensive.get(i), defensive.get((i + 1) % defensive.size()));
        if (pair != null) {
          appendUnique(pairs, pair, MAX_STRATEGIC_PAIRS);
        }
      }
    }
    List<List<int[]>> distribute = List.of(separators, defensiveFiller, defensivePairs);
    int maximum = 0;
    for (List<int[]> cls : distribute) {
      maximum = Math.max(maximum, cls.size());
    }
    for (int index = 0; index < maximum && pairs.size() < MAX_STRATEGIC_PAIRS; index++) {
      for (List<int[]> cls : distribute) {
        if (index < cls.size()) {
          appendUnique(pairs, cls.get(index), MAX_STRATEGIC_PAIRS);
        }
      }
    }
    return pairs;
  }

  private static void addDefensive(List<Pos> defensive, Pos pos) {
    if (defensive.contains(pos)) {
      return;
    }
    if (defensive.size() < 12) {
      defensive.add(pos);
    }
  }

  /** Sort a pair by board index; {@code null} if the two cells coincide. */
  private static int[] normalize(GoState s, Pos a, Pos b) {
    int ia = s.index(a);
    int ib = s.index(b);
    if (ia == ib) {
      return null;
    }
    return ia < ib ? new int[] {ia, ib} : new int[] {ib, ia};
  }

  private static void appendUnique(List<int[]> pairs, int[] pair, int limit) {
    for (int[] existing : pairs) {
      if (existing[0] == pair[0] && existing[1] == pair[1]) {
        return;
      }
    }
    if (pairs.size() < limit) {
      pairs.add(pair);
    }
  }

  private static List<Pos> robustFillers(
      GoState s,
      List<Pos> owned,
      boolean[] cuts,
      boolean[] threatened,
      List<Pos> defensive,
      int limit) {
    List<Pos> result = new ArrayList<>(limit);
    Pos base = s.bases[s.current - 1];
    while (result.size() < limit) {
      Pos best = null;
      int bestScore = -1;
      boolean found = false;
      for (Pos pos : owned) {
        int index = s.index(pos);
        if (cuts[index] || threatened[index] || defensive.contains(pos) || result.contains(pos)) {
          continue;
        }
        int score = Math.abs(pos.row - base.row) + Math.abs(pos.col - base.col);
        if (!found || score > bestScore) {
          best = pos;
          bestScore = score;
          found = true;
        }
      }
      if (!found) {
        break;
      }
      result.add(best);
    }
    return result;
  }

  private static boolean adjacent(Pos a, Pos b) {
    int row = Math.abs(a.row - b.row);
    int col = Math.abs(a.col - b.col);
    return row <= 1 && col <= 1 && !a.equals(b);
  }

  // --- articulation points (Tarjan in G-u), port of position.go's articulationCells ---

  private static final class ArticulationScratch {
    final int[] discovery;
    final int[] low;
    final int[] parent;
    final boolean[] cuts;
    int time;

    ArticulationScratch(int size) {
      discovery = new int[size];
      low = new int[size];
      parent = new int[size];
      cuts = new boolean[size];
    }
  }

  private static boolean[] articulationCells(
      GoState s, boolean[] connected, int excluded, ArticulationScratch work) {
    java.util.Arrays.fill(work.discovery, 0);
    java.util.Arrays.fill(work.low, 0);
    java.util.Arrays.fill(work.cuts, false);
    java.util.Arrays.fill(work.parent, -1);
    work.time = 0;
    int baseIndex = s.index(s.bases[s.current - 1]);
    if (baseIndex != excluded && connected[baseIndex]) {
      visit(s, connected, excluded, work, baseIndex);
    }
    return work.cuts;
  }

  private static void visit(
      GoState s, boolean[] connected, int excluded, ArticulationScratch work, int index) {
    work.time++;
    work.discovery[index] = work.time;
    work.low[index] = work.time;
    int children = 0;
    int row = index / s.cols;
    int col = index % s.cols;
    for (int nr = row - 1; nr <= row + 1; nr++) {
      for (int nc = col - 1; nc <= col + 1; nc++) {
        if ((nr == row && nc == col) || nr < 0 || nr >= s.rows || nc < 0 || nc >= s.cols) {
          continue;
        }
        int nextIndex = nr * s.cols + nc;
        if (nextIndex == excluded || !connected[nextIndex]) {
          continue;
        }
        if (work.discovery[nextIndex] == 0) {
          work.parent[nextIndex] = index;
          children++;
          visit(s, connected, excluded, work, nextIndex);
          if (work.low[nextIndex] < work.low[index]) {
            work.low[index] = work.low[nextIndex];
          }
          if (work.parent[index] == -1 && children > 1) {
            work.cuts[index] = true;
          }
          if (work.parent[index] != -1 && work.low[nextIndex] >= work.discovery[index]) {
            work.cuts[index] = true;
          }
        } else if (nextIndex != work.parent[index] && work.discovery[nextIndex] < work.low[index]) {
          work.low[index] = work.discovery[nextIndex];
        }
      }
    }
  }
}
