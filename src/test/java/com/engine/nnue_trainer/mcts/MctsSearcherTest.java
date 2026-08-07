package com.engine.nnue_trainer.mcts;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.engine.nnue_trainer.board.Action;
import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.board.PlaceNeutralsAction;
import com.engine.nnue_trainer.search.eval.HandTunedEval;
import com.engine.nnue_trainer.search.gobot.GoBotSearcher;
import com.engine.nnue_trainer.search.gobot.GoResult;
import com.engine.nnue_trainer.search.gobot.GoState;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Correctness tests for the Phase 1 PUCT searcher — above all the absolute-frame backup across
 * mover-flip edges, the exact frame mistake that shipped twice in v3 (docs/nnue-v3-net.md "The
 * bug") and the number-one hazard the plan calls out for MCTS on this game.
 */
class MctsSearcherTest {

  private static Board freshBoard() {
    Board board = new Board(12, 12);
    board.setCell(0, 0, new Cell(1, CellKind.BASE));
    board.setCell(11, 11, new Cell(2, CellKind.BASE));
    return board;
  }

  /** P1 massively ahead: base plus a connected diagonal chain of normals; P2 base + one normal. */
  private static Board p1WinningBoard() {
    Board board = freshBoard();
    board.setCell(0, 1, new Cell(1, CellKind.NORMAL));
    board.setCell(1, 1, new Cell(1, CellKind.NORMAL));
    board.setCell(2, 2, new Cell(1, CellKind.NORMAL));
    board.setCell(3, 3, new Cell(1, CellKind.NORMAL));
    board.setCell(4, 4, new Cell(1, CellKind.NORMAL));
    board.setCell(10, 10, new Cell(2, CellKind.NORMAL));
    return board;
  }

  /** Mirror of {@link #p1WinningBoard()}: P2 massively ahead. */
  private static Board p2WinningBoard() {
    Board board = freshBoard();
    board.setCell(11, 10, new Cell(2, CellKind.NORMAL));
    board.setCell(10, 10, new Cell(2, CellKind.NORMAL));
    board.setCell(9, 9, new Cell(2, CellKind.NORMAL));
    board.setCell(8, 8, new Cell(2, CellKind.NORMAL));
    board.setCell(7, 7, new Cell(2, CellKind.NORMAL));
    board.setCell(1, 1, new Cell(1, CellKind.NORMAL));
    return board;
  }

  // --- selection math on a hand-built tree ---

  @Test
  void puctSelectionUsesSignedAbsoluteQ() {
    // Fresh board, P1 to move: exactly 3 legal actions (the base's empty neighbors).
    GoState p1 = GoState.fromBoard(freshBoard(), 1, 3, new boolean[2]);
    MctsSearcher.Config cfg = new MctsSearcher.Config();
    MctsSearcher s1 = new MctsSearcher(p1, cfg);
    MctsSearcher.Node r1 = s1.root();
    assertEquals(3, r1.actions.length, "fresh base has 3 empty neighbors");

    // Hand-built stats: equal priors and visit counts, Q_abs = +0.9 / 0.0 / -0.9. The U terms are
    // identical, so selection is decided by sign(node) * Q_abs alone.
    r1.n[0] = 1;
    r1.n[1] = 1;
    r1.n[2] = 1;
    r1.visits = 3;
    r1.w[0] = 0.9;
    r1.w[1] = 0.0;
    r1.w[2] = -0.9;
    assertEquals(0, s1.select(r1), "P1 node maximizes Q_abs");

    // Same stats at a P2-mover node: sign flips, the most-negative Q_abs wins.
    GoState p2 = GoState.fromBoard(freshBoard(), 2, 3, new boolean[2]);
    MctsSearcher s2 = new MctsSearcher(p2, cfg);
    MctsSearcher.Node r2 = s2.root();
    assertEquals(3, r2.actions.length);
    r2.n[0] = 1;
    r2.n[1] = 1;
    r2.n[2] = 1;
    r2.visits = 3;
    r2.w[0] = 0.9;
    r2.w[1] = 0.0;
    r2.w[2] = -0.9;
    assertEquals(2, s2.select(r2), "P2 node minimizes Q_abs");
  }

  @Test
  void puctSelectionFollowsPriorsWhenUnvisited() {
    GoState state = GoState.fromBoard(freshBoard(), 1, 3, new boolean[2]);
    MctsSearcher s = new MctsSearcher(state, new MctsSearcher.Config());
    MctsSearcher.Node root = s.root();
    root.prior[0] = 0.2f;
    root.prior[1] = 0.5f;
    root.prior[2] = 0.3f;
    assertEquals(1, s.select(root), "all Q are 0 → pure prior-driven U term decides");
  }

  // --- THE critical test: backup signs across mover-flip edges ---

  @Test
  void backupStaysAbsoluteAcrossKeepAndFlipEdges() {
    // P1 winning, P1 to move at turn start: MoveAction children KEEP the mover (movesLeft 3→2),
    // PlaceNeutrals children FLIP it (turn ends) — both edge types under one root, the exact mixed
    // case the v3 emitter got wrong.
    GoState state = GoState.fromBoard(p1WinningBoard(), 1, 3, new boolean[2]);
    assertTrue(
        HandTunedEval.staticEval(state.toBoard(), 1, 3, new boolean[4]) > 0,
        "precondition: P1 frame positive");
    assertTrue(
        HandTunedEval.staticEval(state.toBoard(), 2, 3, new boolean[4]) < 0,
        "precondition: P2 frame negative");

    MctsSearcher.Config cfg = new MctsSearcher.Config();
    MctsSearcher s = new MctsSearcher(state, cfg);
    s.runSims(600);
    MctsSearcher.Node root = s.root();

    boolean sawKeep = false;
    boolean sawFlip = false;
    for (int a = 0; a < root.actions.length; a++) {
      if (root.n[a] == 0 || root.children[a] == null) {
        continue;
      }
      boolean flip = root.children[a].mover != root.mover;
      if (root.actions[a] instanceof PlaceNeutralsAction) {
        assertTrue(flip, "a neutral pair ends the turn");
      }
      // Values are stored in the ABSOLUTE frame: each child's backed-up Q must agree in sign
      // with the child's own absolute-frame leaf value (skipping near-zero ones). The v3-style
      // frame bug — storing/negating in the mover's frame — inverts exactly the flip children
      // (e.g. a self-harming neutral pair, honestly negative-for-P1, would read positive).
      double q = root.w[a] / root.n[a];
      double ref = MctsSearcher.leafValueAbs(root.children[a].state, cfg.valueScale);
      if (Math.abs(ref) < 0.3) {
        continue;
      }
      sawKeep |= !flip;
      sawFlip |= flip;
      assertEquals(
          Math.signum(ref),
          Math.signum(q),
          "child " + root.actions[a] + " (flip=" + flip + "): Q_abs " + q + " vs leaf " + ref);
    }
    assertTrue(sawKeep, "root must have asserted turn-keeping children");
    assertTrue(sawFlip, "root must have asserted turn-flipping children");
    assertTrue(s.rootValueAbs() > 0, "absolute root value positive for the winning P1");
    assertNotNull(s.bestAction());
  }

  @Test
  void backupStaysAbsoluteWhenEveryEdgeFlips() {
    // movesLeft == 1: every legal child ends the turn, so every root edge is a mover-flip edge.
    GoState state = GoState.fromBoard(p1WinningBoard(), 1, 1, new boolean[2]);
    MctsSearcher s = new MctsSearcher(state, new MctsSearcher.Config());
    s.runSims(300);
    MctsSearcher.Node root = s.root();
    for (int a = 0; a < root.actions.length; a++) {
      if (root.n[a] == 0) {
        continue;
      }
      assertEquals(2, root.children[a].mover, "movesLeft=1 child hands the turn to P2");
      assertTrue(root.w[a] / root.n[a] > 0, "flip child Q_abs must stay positive-for-P1");
    }

    // Mirror: P2 winning, P2 to move, all edges flip to P1 — Q_abs must be NEGATIVE (good for
    // P2). Catches an always-positive or root-relative implementation.
    GoState mirror = GoState.fromBoard(p2WinningBoard(), 2, 1, new boolean[2]);
    MctsSearcher m = new MctsSearcher(mirror, new MctsSearcher.Config());
    m.runSims(300);
    MctsSearcher.Node mroot = m.root();
    for (int a = 0; a < mroot.actions.length; a++) {
      if (mroot.n[a] == 0) {
        continue;
      }
      assertEquals(1, mroot.children[a].mover);
      assertTrue(mroot.w[a] / mroot.n[a] < 0, "P2-winning flip child Q_abs must be negative");
    }
    assertTrue(m.rootValueAbs() < 0);
  }

  // --- terminal handling ---

  @Test
  void terminalChildrenBackUpExactOutcome() {
    // P2's base is walled in by fortified P1 cells: P2 is stuck, so P1's very first action
    // triggers elimination and the game ends with winner 1. Every root child is terminal.
    Board board = freshBoard();
    board.setCell(10, 10, new Cell(1, CellKind.FORTIFIED));
    board.setCell(10, 11, new Cell(1, CellKind.FORTIFIED));
    board.setCell(11, 10, new Cell(1, CellKind.FORTIFIED));
    board.setCell(0, 1, new Cell(1, CellKind.NORMAL));
    GoState state = GoState.fromBoard(board, 1, 3, new boolean[2]);

    MctsSearcher s = new MctsSearcher(state, new MctsSearcher.Config());
    s.runSims(50);
    MctsSearcher.Node root = s.root();
    assertEquals(50, s.simsRun());
    for (int a = 0; a < root.actions.length; a++) {
      if (root.n[a] == 0) {
        continue;
      }
      assertTrue(root.children[a].terminal, "every child of the stuck-P2 root is terminal");
      assertEquals(1.0, root.w[a] / root.n[a], 1e-12, "terminal win backs up exactly +1");
    }
    assertEquals(1.0, s.rootValueAbs(), 1e-12);
  }

  @Test
  void terminalValueUsesOutcomeWinnerIncludingTerritoryTie() {
    // Both players sealed in by neutrals: nobody moves. Equal territory → genuine tie → 0.
    Board tie = freshBoard();
    for (int[] rc : new int[][] {{0, 1}, {1, 0}, {1, 1}, {10, 10}, {10, 11}, {11, 10}}) {
      tie.setCell(rc[0], rc[1], new Cell(0, CellKind.NEUTRAL));
    }
    GoState tieState = GoState.fromBoard(tie, 1, 3, new boolean[2]);
    assertEquals(0, tieState.outcomeWinner(), "precondition: equal-territory tie");
    assertEquals(0.0, MctsSearcher.terminalValueAbs(tieState), 1e-12);

    // Same sealed board but P1 holds one extra (disconnected) cell: territory tiebreak → P1.
    Board p1More = freshBoard();
    for (int[] rc : new int[][] {{0, 1}, {1, 0}, {1, 1}, {10, 10}, {10, 11}, {11, 10}}) {
      p1More.setCell(rc[0], rc[1], new Cell(0, CellKind.NEUTRAL));
    }
    p1More.setCell(5, 5, new Cell(1, CellKind.NORMAL));
    GoState p1State = GoState.fromBoard(p1More, 1, 3, new boolean[2]);
    assertEquals(1.0, MctsSearcher.terminalValueAbs(p1State), 1e-12);
    assertEquals(
        -1.0,
        MctsSearcher.terminalValueAbs(GoState.fromBoard(mirrorExtra(), 1, 3, new boolean[2])),
        1e-12);

    // A stuck root is terminal: no sims run, no action.
    MctsSearcher s = new MctsSearcher(tieState, new MctsSearcher.Config());
    s.runSims(10);
    assertEquals(0, s.simsRun());
    assertEquals(null, s.bestAction());
  }

  private static Board mirrorExtra() {
    Board b = freshBoard();
    for (int[] rc : new int[][] {{0, 1}, {1, 0}, {1, 1}, {10, 10}, {10, 11}, {11, 10}}) {
      b.setCell(rc[0], rc[1], new Cell(0, CellKind.NEUTRAL));
    }
    b.setCell(5, 5, new Cell(2, CellKind.NORMAL));
    return b;
  }

  // --- determinism ---

  @Test
  void reproducibleUnderFixedSeed() {
    GoState state = GoState.fromBoard(p1WinningBoard(), 1, 3, new boolean[2]);
    MctsSearcher.Config cfg = new MctsSearcher.Config();
    cfg.seed = 42;
    MctsSearcher a = new MctsSearcher(state, cfg);
    MctsSearcher b = new MctsSearcher(state, cfg);
    a.runSims(300);
    b.runSims(300);
    assertEquals(a.bestAction(), b.bestAction());
    assertArrayEquals(a.root().n, b.root().n, "identical visit distribution");

    // Root noise is seeded too: same seed → still byte-identical.
    MctsSearcher.Config noisy = new MctsSearcher.Config();
    noisy.seed = 42;
    noisy.rootNoise = true;
    MctsSearcher c = new MctsSearcher(state, noisy);
    MctsSearcher d = new MctsSearcher(state, noisy);
    c.runSims(300);
    d.runSims(300);
    assertEquals(c.bestAction(), d.bestAction());
    assertArrayEquals(c.root().n, d.root().n);
  }

  // --- smoke: 1000 sims on a mid-game position ---

  @Test
  void smokeMidGamePicksSaneMoveAt1000Sims() {
    // Deterministic mid-game: 12 plies of fixed-depth-2 clone play from the fresh board
    // (chooseDepth never consults the opening book).
    GoState state = GoState.fromBoard(freshBoard(), 1, 3, new boolean[2]);
    for (int ply = 0; ply < 12 && !state.gameOver(); ply++) {
      GoResult r = GoBotSearcher.chooseDepth(state, 2);
      assertNotNull(r);
      state = state.apply(r.action);
      assertNotNull(state);
    }
    List<Action> legal = state.legalActions();
    assertTrue(legal.size() >= 3, "mid-game position should have real branching");

    MctsSearcher s = new MctsSearcher(state, new MctsSearcher.Config());
    long t0 = System.nanoTime();
    s.runSims(1000);
    double secs = (System.nanoTime() - t0) / 1e9;
    Action chosen = s.bestAction();
    assertNotNull(chosen);
    assertTrue(legal.contains(chosen), "chosen action must be legal");
    GoState next = state.apply(chosen);
    assertNotNull(next);
    assertTrue(next.active(state.currentPlayer()), "sane move: does not eliminate the mover");

    // The search actually discriminated: the best action got more than a uniform share of visits.
    MctsSearcher.Node root = s.root();
    int bestN = 0;
    for (int a = 0; a < root.actions.length; a++) {
      bestN = Math.max(bestN, root.n[a]);
    }
    assertTrue(bestN > 1000 / root.actions.length, "visits concentrated above uniform share");
    System.out.printf(
        "MCTS smoke: %d sims in %.3fs (%.0f sims/s), %d actions, chose %s (visits %d)%n",
        s.simsRun(), secs, s.simsRun() / secs, root.actions.length, chosen, bestN);
  }
}
