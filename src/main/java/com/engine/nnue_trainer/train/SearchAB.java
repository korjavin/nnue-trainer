package com.engine.nnue_trainer.train;

import com.engine.nnue_trainer.board.Action;
import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.board.MoveGenerator;
import com.engine.nnue_trainer.nnue.NNUEModel;
import com.engine.nnue_trainer.search.SearchEngine;
import com.engine.nnue_trainer.search.baseline.BaselineSearchEngine;
import java.util.List;

/**
 * A/B: does the #38 search upgrade (TT/PVS/ordering/quiescence/book) actually play STRONGER than
 * the pre-#38 naive alpha-beta, holding the eval fixed? Both sides use the same distilled NNUEModel
 * and the same per-move time budget, so any win-rate gap is pure search quality. Simplified
 * self-play rules (1 action/turn, as in SelfPlayGenerator) - consistent for both engines, so the
 * comparison is fair.
 *
 * <p>Run: ./mvnw -q compile && java -cp target/classes com.engine.nnue_trainer.train.SearchAB
 * [games] [ms]
 */
public class SearchAB {

  interface Chooser {
    Action choose(Board board, int player);
  }

  public static void main(String[] args) {
    int games = args.length > 0 ? Integer.parseInt(args[0]) : 12;
    int timeMs = args.length > 1 ? Integer.parseInt(args[1]) : 500;
    int maxTurns = 400;

    NNUEModel model = NNUEModel.createDefault(); // distilled weights on the classpath
    SearchEngine neu = new SearchEngine(model);
    BaselineSearchEngine old = new BaselineSearchEngine(model);
    Chooser newC = (b, p) -> neu.findBestActionWithTimeLimitUsingModel(b, p, timeMs, true).bestAction;
    Chooser oldC = (b, p) -> old.findBestActionWithTimeLimitUsingModel(b, p, timeMs, true).bestAction;

    int newWins = 0, oldWins = 0, draws = 0;
    for (int g = 0; g < games; g++) {
      boolean newIsP1 = (g % 2 == 0);
      Chooser p1 = newIsP1 ? newC : oldC;
      Chooser p2 = newIsP1 ? oldC : newC;
      int winner = playGame(neu, p1, p2, maxTurns);
      if (winner == 0) {
        draws++;
      } else if ((winner == 1) == newIsP1) {
        newWins++;
      } else {
        oldWins++;
      }
      System.out.printf(
          "game %2d: winner=p%d (new was p%d) | running NEW %d - %d OLD (%d draws)%n",
          g + 1, winner, newIsP1 ? 1 : 2, newWins, oldWins, draws);
      System.out.flush();
    }
    System.out.printf(
        "%n=== A/B (same distilled eval, %dms/move): NEW %d - %d OLD, %d draws over %d games ===%n",
        timeMs, newWins, oldWins, draws, games);
  }

  private static int playGame(SearchEngine ref, Chooser p1, Chooser p2, int maxTurns) {
    Board board = new Board(12, 12);
    board.setCell(0, 0, new Cell(1, CellKind.BASE));
    board.setCell(11, 11, new Cell(2, CellKind.BASE));
    int cur = 1;
    for (int t = 0; t < maxTurns; t++) {
      for (int m = 0; m < 3; m++) { // 3 moves per turn, as the real (server-driven) game
        List<Action> legal = MoveGenerator.getLegalActions(cur, board, true);
        if (legal.isEmpty()) {
          return 3 - cur; // current player cannot move -> opponent wins
        }
        Action a = (cur == 1 ? p1 : p2).choose(board, cur);
        if (a == null) {
          a = legal.get(0);
        }
        board = SearchEngine.applyAction(board, cur, a);
        if (ref.isTerminal(board)) {
          return determineWinner(board);
        }
      }
      cur = 3 - cur;
    }
    return 0; // draw (hit turn cap)
  }

  private static int determineWinner(Board board) {
    boolean p1 = false, p2 = false;
    for (int r = 0; r < board.rows; r++) {
      for (int c = 0; c < board.cols; c++) {
        Cell cell = board.getCell(r, c);
        if (cell != null && cell.kind == CellKind.BASE) {
          if (cell.owner == 1) p1 = true;
          if (cell.owner == 2) p2 = true;
        }
      }
    }
    if (p1 && !p2) return 1;
    if (!p1 && p2) return 2;
    return 0;
  }
}
