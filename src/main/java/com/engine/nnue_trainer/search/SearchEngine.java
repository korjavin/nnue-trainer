package com.engine.nnue_trainer.search;

import com.engine.nnue_trainer.board.Action;
import com.engine.nnue_trainer.board.BaseConnectionSearch;
import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.board.MoveAction;
import com.engine.nnue_trainer.board.MoveGenerator;
import com.engine.nnue_trainer.board.PlaceNeutralsAction;
import com.engine.nnue_trainer.board.Pos;
import com.engine.nnue_trainer.nnue.Accumulator;
import com.engine.nnue_trainer.nnue.BoardFeatureMapper;
import com.engine.nnue_trainer.nnue.NNUEModel;
import java.util.ArrayList;
import java.util.List;

public class SearchEngine {

  private NNUEModel nnueModel;
  private static int nodesEvaluated = 0;

  public SearchEngine() {
    this.nnueModel = NNUEModel.createDefault();
  }

  public SearchEngine(NNUEModel nnueModel) {
    this.nnueModel = nnueModel;
  }

  public void setNnueModel(NNUEModel nnueModel) {
    this.nnueModel = nnueModel;
  }

  /**
   * Executes alpha-beta minimax search.
   *
   * @param board The current state of the board.
   * @param depth The current search depth (starts at max and goes down to 0).
   * @param alpha The alpha value for pruning (best already explored option along path to root for
   *     maximizer).
   * @param beta The beta value for pruning (best already explored option along path to root for
   *     minimizer).
   * @param player The active player's ID (e.g. 1 or -1, or 1 or 2).
   * @param maximizingPlayer True if the current node is a maximizing node.
   * @return The evaluation score of the board.
   */
  public float alphaBeta(
      Board board, int depth, float alpha, float beta, int player, boolean maximizingPlayer) {
    return alphaBeta(board, depth, alpha, beta, player, maximizingPlayer, 0, Long.MAX_VALUE);
  }

  public float alphaBeta(
      Board board,
      int depth,
      float alpha,
      float beta,
      int player,
      boolean maximizingPlayer,
      long startTime,
      long timeLimitMs) {
    return alphaBeta(
        board, null, depth, alpha, beta, player, maximizingPlayer, startTime, timeLimitMs);
  }

  public float alphaBeta(
      Board board,
      Accumulator accumulator,
      int depth,
      float alpha,
      float beta,
      int player,
      boolean maximizingPlayer,
      long startTime,
      long timeLimitMs) {
    if (System.currentTimeMillis() - startTime >= timeLimitMs) {
      throw new SearchTimeoutException();
    }

    if (depth == 0 || isTerminal(board)) {
      return evaluate(board, accumulator, player, maximizingPlayer);
    }

    List<Board> nextBoards = generateNextBoards(board, player, maximizingPlayer);

    // If there are no moves available, we treat it as terminal
    if (nextBoards.isEmpty()) {
      return evaluate(board, accumulator, player, maximizingPlayer);
    }

    int originalPlayer = maximizingPlayer ? player : getOpponent(player);

    if (maximizingPlayer) {
      float maxEval = Float.NEGATIVE_INFINITY;
      for (Board child : nextBoards) {
        Accumulator childAcc = null;
        if (accumulator != null) {
          childAcc = accumulator.copy();
          Accumulator.computeDiff(board, child, childAcc, originalPlayer, nnueModel);
        }
        // Opponent's turn next, so maximizingPlayer becomes false.
        float eval =
            alphaBeta(
                child,
                childAcc,
                depth - 1,
                alpha,
                beta,
                getOpponent(player),
                false,
                startTime,
                timeLimitMs);
        maxEval = Math.max(maxEval, eval);
        alpha = Math.max(alpha, eval);
        if (beta <= alpha) {
          break; // Beta cutoff
        }
      }
      return maxEval;
    } else {
      float minEval = Float.POSITIVE_INFINITY;
      for (Board child : nextBoards) {
        Accumulator childAcc = null;
        if (accumulator != null) {
          childAcc = accumulator.copy();
          Accumulator.computeDiff(board, child, childAcc, originalPlayer, nnueModel);
        }
        // Maximizing player's turn next, so maximizingPlayer becomes true.
        float eval =
            alphaBeta(
                child,
                childAcc,
                depth - 1,
                alpha,
                beta,
                getOpponent(player),
                true,
                startTime,
                timeLimitMs);
        minEval = Math.min(minEval, eval);
        beta = Math.min(beta, eval);
        if (beta <= alpha) {
          break; // Alpha cutoff
        }
      }
      return minEval;
    }
  }

  protected int getOpponent(int player) {
    return 3 - player;
  }

  /** Terminal check: if either player has lost their base. */
  public boolean isTerminal(Board board) {
    boolean hasBases = false;
    boolean player1Base = false;
    boolean player2Base = false;
    for (int r = 0; r < board.rows; r++) {
      for (int c = 0; c < board.cols; c++) {
        Cell cell = board.getCell(r, c);
        if (cell != null && cell.kind == CellKind.BASE) {
          hasBases = true;
          if (cell.owner == 1) player1Base = true;
          if (cell.owner == 2) player2Base = true;
        }
      }
    }
    if (!hasBases) return false;
    return !player1Base || !player2Base;
  }

  protected float evaluate(Board board, int player, boolean maximizingPlayer) {
    return evaluate(board, null, player, maximizingPlayer);
  }

  /** Evaluation function: simple count of pieces owned by the player, penalized if base is lost. */
  protected float evaluate(
      Board board, Accumulator accumulator, int player, boolean maximizingPlayer) {
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

    if (nnueModel != null && board.rows == 12 && board.cols == 12) {
      if (accumulator != null) {
        return nnueModel.forward(accumulator);
      } else {
        float[] features = BoardFeatureMapper.map(board, originalPlayer);
        return nnueModel.forward(features);
      }
    }

    return myPieces - oppPieces;
  }

  public static Board applyAction(Board board, int player, Action action) {
    Board nextBoard = new Board(board.rows, board.cols);
    for (int r = 0; r < board.rows; r++) {
      for (int c = 0; c < board.cols; c++) {
        Cell cell = board.getCell(r, c);
        if (cell != null) {
          nextBoard.setCell(r, c, new Cell(cell.owner, cell.kind));
        }
      }
    }

    if (action instanceof MoveAction) {
      Pos target = ((MoveAction) action).target;
      nextBoard.setCell(target.row, target.col, new Cell(player, CellKind.NORMAL));
    } else if (false) {
      Pos pos1 = ((PlaceNeutralsAction) action).pos1;
      Pos pos2 = ((PlaceNeutralsAction) action).pos2;
      nextBoard.setCell(pos1.row, pos1.col, new Cell(0, CellKind.NEUTRAL));
      nextBoard.setCell(pos2.row, pos2.col, new Cell(0, CellKind.NEUTRAL));
    }

    // Clean up disconnected cells for all players
    for (int p = 1; p <= 2; p++) {
      boolean[] connected = BaseConnectionSearch.connected(p, nextBoard);
      for (int r = 0; r < nextBoard.rows; r++) {
        for (int c = 0; c < nextBoard.cols; c++) {
          Cell cell = nextBoard.getCell(r, c);
          if (cell != null
              && cell.owner == p
              && cell.kind != CellKind.EMPTY
              && cell.kind != CellKind.NEUTRAL) {
            if (!connected[r * nextBoard.cols + c]) {
              nextBoard.setCell(r, c, new Cell(0, CellKind.EMPTY));
            }
          }
        }
      }
    }

    return nextBoard;
  }

  protected List<Action> orderActions(List<Action> actions, Board board, int player) {
    int opponent = getOpponent(player);

    actions.sort(
        (a1, a2) -> {
          int score1 = scoreAction(a1, board, opponent);
          int score2 = scoreAction(a2, board, opponent);
          return Integer.compare(score2, score1);
        });
    return actions;
  }

  private int scoreAction(Action action, Board board, int opponent) {
    if (action instanceof MoveAction) {
      Pos target = ((MoveAction) action).target;
      Cell targetCell = board.getCell(target.row, target.col);

      if (targetCell != null && targetCell.owner == opponent) {
        if (targetCell.kind == CellKind.BASE) {
          return 10000;
        } else if (targetCell.kind == CellKind.NORMAL) {
          return 1000;
        }
      }

      // Check for adjacency to opponent base
      for (int dr = -1; dr <= 1; dr++) {
        for (int dc = -1; dc <= 1; dc++) {
          if (dr == 0 && dc == 0) continue;
          Cell adjCell = board.getCell(target.row + dr, target.col + dc);
          if (adjCell != null && adjCell.owner == opponent && adjCell.kind == CellKind.BASE) {
            return 100;
          }
        }
      }
    }
    return 0;
  }

  protected List<Board> generateNextBoards(Board board, int player, boolean maximizingPlayer) {
    List<Action> actions = MoveGenerator.getLegalActions(player, board, false);
    actions = orderActions(actions, board, player);
    List<Board> boards = new ArrayList<>();
    for (Action action : actions) {
      boards.add(applyAction(board, player, action));
    }
    return boards;
  }

  public SearchResult findBestAction(Board board, int player, int depth, boolean canPlaceNeutral) {
    List<Action> actions = MoveGenerator.getLegalActions(player, board, canPlaceNeutral);
    if (actions.isEmpty()) {
      return new SearchResult(null, Float.NEGATIVE_INFINITY, depth, 0, 0);
    }

    long startTime = System.currentTimeMillis();
    nodesEvaluated = 0;

    actions = this.orderActions(actions, board, player);

    Action bestAction = null;
    float bestValue = Float.NEGATIVE_INFINITY;

    Accumulator rootAcc = null;
    if (this.nnueModel != null && board.rows == 12 && board.cols == 12) {
      rootAcc = new Accumulator();
      rootAcc.init(board, player, this.nnueModel);
    }

    for (Action action : actions) {
      Board child = applyAction(board, player, action);
      Accumulator childAcc = null;
      if (rootAcc != null) {
        childAcc = rootAcc.copy();
        Accumulator.computeDiff(board, child, childAcc, player, this.nnueModel);
      }
      float value =
          this.alphaBeta(
              child,
              childAcc,
              depth - 1,
              Float.NEGATIVE_INFINITY,
              Float.POSITIVE_INFINITY,
              3 - player,
              false,
              startTime,
              Long.MAX_VALUE);
      if (value > bestValue) {
        bestValue = value;
        bestAction = action;
      }
    }

    long elapsedTime = System.currentTimeMillis() - startTime;
    System.out.println("=== Search Diagnostics ===");
    System.out.println("Search Depth: " + depth);
    System.out.println("Nodes Evaluated: " + nodesEvaluated);
    System.out.println("Time Elapsed: " + elapsedTime + " ms");
    String actionDesc = (bestAction != null) ? bestAction.toString() : "None";
    System.out.println("Best Action: " + actionDesc);
    System.out.println("Position Evaluation: " + bestValue);
    System.out.println("==========================");

    return new SearchResult(bestAction, bestValue, depth, nodesEvaluated, elapsedTime);
  }

  public SearchResult findBestActionWithTimeLimit(
      Board board, int player, long timeLimitMs, boolean canPlaceNeutral) {
    long startTime = System.currentTimeMillis();
    nodesEvaluated = 0;
    Action globalBestAction = null;
    float globalBestScore = Float.NEGATIVE_INFINITY;
    int maxDepthReached = 0;

    // We get the legal actions once
    List<Action> actions = MoveGenerator.getLegalActions(player, board, canPlaceNeutral);
    if (actions.isEmpty()) {
      return new SearchResult(null, Float.NEGATIVE_INFINITY, 0, 0, 0);
    }

    // Fallback: simply pick the first available action in case depth 1 doesn't even finish
    globalBestAction = actions.get(0);

    Accumulator rootAcc = null;
    if (this.nnueModel != null && board.rows == 12 && board.cols == 12) {
      rootAcc = new Accumulator();
      rootAcc.init(board, player, this.nnueModel);
    }

    for (int depth = 1; depth <= 20; depth++) {
      try {
        Action bestActionAtDepth = null;
        float bestValueAtDepth = Float.NEGATIVE_INFINITY;

        for (Action action : actions) {
          Board child = applyAction(board, player, action);
          Accumulator childAcc = null;
          if (rootAcc != null) {
            childAcc = rootAcc.copy();
            Accumulator.computeDiff(board, child, childAcc, player, this.nnueModel);
          }
          float value =
              this.alphaBeta(
                  child,
                  childAcc,
                  depth - 1,
                  Float.NEGATIVE_INFINITY,
                  Float.POSITIVE_INFINITY,
                  3 - player,
                  false,
                  startTime,
                  timeLimitMs);
          if (value > bestValueAtDepth) {
            bestValueAtDepth = value;
            bestActionAtDepth = action;
          }
        }
        // If we completed this depth without throwing SearchTimeoutException,
        // we can safely update the global best action.
        if (bestActionAtDepth != null) {
          globalBestAction = bestActionAtDepth;
          globalBestScore = bestValueAtDepth;
          maxDepthReached = depth;
        }
      } catch (SearchTimeoutException e) {
        // Time is up, break out of IDDFS loop
        break;
      }
    }

    long elapsedTime = System.currentTimeMillis() - startTime;

    return new SearchResult(
        globalBestAction, globalBestScore, maxDepthReached, nodesEvaluated, elapsedTime);
  }
}
