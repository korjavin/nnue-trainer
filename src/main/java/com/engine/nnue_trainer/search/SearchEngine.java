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
import com.engine.nnue_trainer.nnue.BoardFeatureMapper;
import com.engine.nnue_trainer.nnue.NNUEModel;
import java.util.ArrayList;
import java.util.List;

public class SearchEngine {

  private NNUEModel nnueModel;

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
    if (depth == 0 || isTerminal(board)) {
      return evaluate(board, player, maximizingPlayer);
    }

    List<Board> nextBoards = generateNextBoards(board, player, maximizingPlayer);

    // If there are no moves available, we treat it as terminal
    if (nextBoards.isEmpty()) {
      return evaluate(board, player, maximizingPlayer);
    }

    if (maximizingPlayer) {
      float maxEval = Float.NEGATIVE_INFINITY;
      for (Board child : nextBoards) {
        // Opponent's turn next, so maximizingPlayer becomes false.
        float eval = alphaBeta(child, depth - 1, alpha, beta, getOpponent(player), false);
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
        // Maximizing player's turn next, so maximizingPlayer becomes true.
        float eval = alphaBeta(child, depth - 1, alpha, beta, getOpponent(player), true);
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
  protected boolean isTerminal(Board board) {
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

  /** Evaluation function: simple count of pieces owned by the player, penalized if base is lost. */
  protected float evaluate(Board board, int player, boolean maximizingPlayer) {
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
      float[] features = BoardFeatureMapper.map(board, originalPlayer);
      return nnueModel.forward(features);
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
    } else if (action instanceof PlaceNeutralsAction) {
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

  protected List<Board> generateNextBoards(Board board, int player, boolean maximizingPlayer) {
    List<Action> actions = MoveGenerator.getLegalActions(player, board, false);
    List<Board> boards = new ArrayList<>();
    for (Action action : actions) {
      boards.add(applyAction(board, player, action));
    }
    return boards;
  }

  public static Action findBestAction(Board board, int player, int depth, boolean canPlaceNeutral) {
    List<Action> actions = MoveGenerator.getLegalActions(player, board, canPlaceNeutral);
    if (actions.isEmpty()) {
      return null;
    }

    SearchEngine engine = new SearchEngine();
    Action bestAction = null;
    float bestValue = Float.NEGATIVE_INFINITY;

    for (Action action : actions) {
      Board child = applyAction(board, player, action);
      float value =
          engine.alphaBeta(
              child,
              depth - 1,
              Float.NEGATIVE_INFINITY,
              Float.POSITIVE_INFINITY,
              3 - player,
              false);
      if (value > bestValue) {
        bestValue = value;
        bestAction = action;
      }
    }
    return bestAction;
  }
}
