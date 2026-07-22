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
  // Debug toggles (system properties) for bisecting the #38 search regression (raz.2.3).
  public static boolean USE_QUIESCENCE =
      !"false".equalsIgnoreCase(System.getProperty("USE_QUIESCENCE"));
  public static boolean USE_TT = !"false".equalsIgnoreCase(System.getProperty("USE_TT"));

  private NNUEModel nnueModel;
  private boolean isCustomModel = false;
  private int nodesEvaluated = 0;

  // Search state
  private TranspositionTable tt;
  private HistoryTable historyTable;
  private KillerMoves killerMoves;

  public SearchEngine() {
    this.nnueModel = NNUEModel.createDefault();
    this.isCustomModel = false;
    initSearchState();
  }

  public SearchEngine(NNUEModel nnueModel) {
    this.nnueModel = nnueModel;
    this.isCustomModel = true;
    initSearchState();
  }

  private void initSearchState() {
    // 32MB transposition table (approx 1 million entries)
    this.tt = new TranspositionTable(20);
    this.historyTable = new HistoryTable();
    this.killerMoves = new KillerMoves();
  }

  public void clearSearchState() {
    tt.clear();
    historyTable.clear();
    killerMoves.clear();
  }

  public void setNnueModel(NNUEModel nnueModel) {
    this.nnueModel = nnueModel;
  }

  public NNUEModel getNnueModel() {
    return nnueModel;
  }

  /**
   * Executes alpha-beta negamax search.
   *
   * <p>Scores are always relative to the side to move ({@code player}): a position good for the
   * side to move is positive. The parent therefore negates the child's returned score
   * ({@code score = -alphaBeta(child, -beta, -alpha, opponent)}). The static evaluation is computed
   * from a single fixed {@code perspective} (the root player, so the incremental NNUE accumulator
   * can stay valid down the whole tree); the leaf flips that fixed score to the side-to-move frame.
   *
   * @param board The current state of the board.
   * @param accumulator Incremental NNUE accumulator (in {@code perspective}'s frame), or null.
   * @param depth The current search depth (starts at max and goes down to 0).
   * @param alpha The lower bound of the search window (side-to-move relative).
   * @param beta The upper bound of the search window (side-to-move relative).
   * @param player The side to move at this node.
   * @param perspective The fixed player whose frame the accumulator / evaluate() use.
   * @return The negamax value of the board, relative to {@code player}.
   */
  public float alphaBeta(
      Board board,
      Accumulator accumulator,
      int depth,
      float alpha,
      float beta,
      int player,
      int perspective,
      long startTime,
      long timeLimitMs) {
    if (System.currentTimeMillis() - startTime >= timeLimitMs) {
      throw new SearchTimeoutException();
    }

    // Key the TT by the side to move: negamax scores are side-to-move relative, so the same board
    // with a different player to move is a different entry.
    long zobristKey = Zobrist.computeHash(board, player);
    float alphaOrig = alpha;

    // TT Probe (negamax bound convention).
    TTEntry tte = USE_TT ? tt.probe(zobristKey) : null;
    Action ttMove = null;
    if (tte != null && tte.depth >= depth) {
      if (tte.flag == TTEntry.FLAG_EXACT) {
        return tte.score;
      } else if (tte.flag == TTEntry.FLAG_LOWER_BOUND) {
        alpha = Math.max(alpha, tte.score);
      } else if (tte.flag == TTEntry.FLAG_UPPER_BOUND) {
        beta = Math.min(beta, tte.score);
      }
      if (alpha >= beta) {
        return tte.score;
      }
      ttMove = tte.bestAction;
    }

    if (depth == 0 || isTerminal(board)) {
      if (USE_QUIESCENCE) {
        return quiescenceSearch(
            board, accumulator, alpha, beta, player, perspective, startTime, timeLimitMs);
      }
      return leafEval(board, accumulator, player, perspective);
    }

    List<Action> actions = MoveGenerator.getLegalActions(player, board, false);
    if (actions.isEmpty()) {
      return leafEval(board, accumulator, player, perspective);
    }

    actions = orderActions(actions, board, player, ttMove, depth);

    float bestScore = Float.NEGATIVE_INFINITY;
    Action bestAction = null;
    boolean firstMove = true;

    for (Action action : actions) {
      Board child = applyAction(board, player, action);
      Accumulator childAcc = null;
      if (accumulator != null) {
        childAcc = accumulator.copy();
        Accumulator.computeDiff(board, child, childAcc, perspective, nnueModel);
      }

      float score;
      if (firstMove) {
        // Full window search for the principal variation.
        score =
            -alphaBeta(
                child,
                childAcc,
                depth - 1,
                -beta,
                -alpha,
                getOpponent(player),
                perspective,
                startTime,
                timeLimitMs);
        firstMove = false;
      } else {
        // Null window (PVS) search.
        score =
            -alphaBeta(
                child,
                childAcc,
                depth - 1,
                -alpha - 1,
                -alpha,
                getOpponent(player),
                perspective,
                startTime,
                timeLimitMs);
        if (score > alpha && score < beta) { // Re-search on fail-high.
          score =
              -alphaBeta(
                  child,
                  childAcc,
                  depth - 1,
                  -beta,
                  -alpha,
                  getOpponent(player),
                  perspective,
                  startTime,
                  timeLimitMs);
        }
      }

      if (score > bestScore) {
        bestScore = score;
        bestAction = action;
      }
      if (score > alpha) {
        alpha = score;
      }
      if (alpha >= beta) {
        // Beta cutoff (fail-high).
        if (!isCaptureOrThreat(board, player, action)) {
          killerMoves.addKiller(depth, action);
          historyTable.addBonus(player, action, depth);
        }
        break;
      }
    }

    if (USE_TT) {
      byte ttFlag;
      if (bestScore <= alphaOrig) {
        ttFlag = TTEntry.FLAG_UPPER_BOUND; // Fail-low: no move raised alpha.
      } else if (bestScore >= beta) {
        ttFlag = TTEntry.FLAG_LOWER_BOUND; // Fail-high: beta cutoff.
      } else {
        ttFlag = TTEntry.FLAG_EXACT;
      }
      tt.store(zobristKey, bestAction, bestScore, depth, ttFlag);
    }
    return bestScore;
  }

  /** Static leaf value in the side-to-move ({@code player}) frame. */
  private float leafEval(Board board, Accumulator accumulator, int player, int perspective) {
    float e = evaluate(board, accumulator, perspective);
    return (player == perspective) ? e : -e;
  }

  public float quiescenceSearch(
      Board board,
      Accumulator accumulator,
      float alpha,
      float beta,
      int player,
      int perspective,
      long startTime,
      long timeLimitMs) {
    return quiescenceSearch(
        board, accumulator, alpha, beta, player, perspective, startTime, timeLimitMs, 0);
  }

  public float quiescenceSearch(
      Board board,
      Accumulator accumulator,
      float alpha,
      float beta,
      int player,
      int perspective,
      long startTime,
      long timeLimitMs,
      int qsDepth) {

    if (qsDepth >= 6 || System.currentTimeMillis() - startTime >= timeLimitMs) {
      return leafEval(board, accumulator, player, perspective);
    }

    // Negamax stand-pat: side-to-move relative.
    float standPat = leafEval(board, accumulator, player, perspective);
    if (standPat >= beta) return standPat;
    if (standPat > alpha) alpha = standPat;

    List<Action> actions = MoveGenerator.getLegalActions(player, board, false);
    List<Action> loudActions = new ArrayList<>();
    for (Action a : actions) {
      if (isCaptureOrThreat(board, player, a)) {
        loudActions.add(a);
      }
    }

    if (loudActions.isEmpty() || isTerminal(board)) {
      return standPat;
    }

    loudActions = orderActions(loudActions, board, player, null, 0);

    float bestScore = standPat;
    for (Action action : loudActions) {
      Board child = applyAction(board, player, action);
      Accumulator childAcc = null;
      if (accumulator != null) {
        childAcc = accumulator.copy();
        Accumulator.computeDiff(board, child, childAcc, perspective, nnueModel);
      }

      float score =
          -quiescenceSearch(
              child,
              childAcc,
              -beta,
              -alpha,
              getOpponent(player),
              perspective,
              startTime,
              timeLimitMs,
              qsDepth + 1);
      if (score > bestScore) bestScore = score;
      if (score > alpha) alpha = score;
      if (alpha >= beta) break;
    }
    return bestScore;
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

  protected float evaluate(Board board, int player) {
    return evaluate(board, null, player);
  }

  /**
   * Static evaluation from {@code player}'s perspective: positive is good for {@code player}. Simple
   * count of pieces owned by the player, penalized if base is lost, or the NNUE forward pass.
   */
  protected float evaluate(Board board, Accumulator accumulator, int player) {
    nodesEvaluated++;
    int originalPlayer = player;
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

  protected List<Action> orderActions(
      List<Action> actions, Board board, int player, Action ttMove, int depth) {
    int opponent = getOpponent(player);

    actions.sort(
        (a1, a2) -> {
          int score1 = scoreAction(a1, board, player, opponent, ttMove, depth);
          int score2 = scoreAction(a2, board, player, opponent, ttMove, depth);
          return Integer.compare(score2, score1);
        });
    return actions;
  }

  // Deprecated overload for backward compatibility with tests
  protected List<Action> orderActions(List<Action> actions, Board board, int player) {
    return orderActions(actions, board, player, null, 0);
  }

  private int scoreAction(
      Action action, Board board, int player, int opponent, Action ttMove, int depth) {
    if (ttMove != null && action.equals(ttMove)) {
      return 1000000; // Search TT move first
    }

    boolean isCapture = false;

    if (action instanceof MoveAction) {
      Pos target = ((MoveAction) action).target;
      Cell targetCell = board.getCell(target.row, target.col);

      if (targetCell != null && targetCell.owner == opponent) {
        isCapture = true;
        if (targetCell.kind == CellKind.BASE) {
          return 500000; // High priority for base captures
        } else if (targetCell.kind == CellKind.NORMAL) {
          return 400000; // Normal captures
        }
      }

      // Check for adjacency to opponent base
      if (!isCapture) {
        for (int dr = -1; dr <= 1; dr++) {
          for (int dc = -1; dc <= 1; dc++) {
            if (dr == 0 && dc == 0) continue;
            Cell adjCell = board.getCell(target.row + dr, target.col + dc);
            if (adjCell != null && adjCell.owner == opponent && adjCell.kind == CellKind.BASE) {
              return 300000; // Threat to base
            }
          }
        }
      }
    }

    // If it's a quiet move, use killer moves and history heuristic
    if (killerMoves.isKiller(depth, action)) {
      return 200000;
    }

    return historyTable.getScore(player, action);
  }

  protected boolean isCaptureOrThreat(Board board, int player, Action action) {
    if (!(action instanceof MoveAction)) return false;
    MoveAction move = (MoveAction) action;
    int opponent = getOpponent(player);

    Cell targetCell = board.getCell(move.target);
    if (targetCell != null && targetCell.owner == opponent) {
      return true; // Capture
    }

    // Check threat to base
    for (int dr = -1; dr <= 1; dr++) {
      for (int dc = -1; dc <= 1; dc++) {
        if (dr == 0 && dc == 0) continue;
        Cell adjCell = board.getCell(move.target.row + dr, move.target.col + dc);
        if (adjCell != null && adjCell.owner == opponent && adjCell.kind == CellKind.BASE) {
          return true;
        }
      }
    }
    return false;
  }

  protected List<Board> generateNextBoards(Board board, int player, boolean maximizingPlayer) {
    List<Action> actions = MoveGenerator.getLegalActions(player, board, false);
    actions = orderActions(actions, board, player, null, 0);
    List<Board> boards = new ArrayList<>();
    for (Action action : actions) {
      boards.add(applyAction(board, player, action));
    }
    return boards;
  }

  public static SearchResult findBestAction(
      Board board, int player, int depth, boolean canPlaceNeutral) {
    return new SearchEngine().findBestActionUsingModel(board, player, depth, canPlaceNeutral);
  }

  public SearchResult findBestActionUsingModel(
      Board board, int player, int depth, boolean canPlaceNeutral) {
    List<Action> actions = MoveGenerator.getLegalActions(player, board, canPlaceNeutral);
    if (actions.isEmpty()) {
      return new SearchResult(null, Float.NEGATIVE_INFINITY, depth, 0, 0);
    }

    long startTime = System.currentTimeMillis();
    nodesEvaluated = 0;

    if (!isCustomModel) {
      Action randomOpening = OpeningBook.getRandomOpeningMove(board, player, canPlaceNeutral);
      if (randomOpening != null) {
        return new SearchResult(randomOpening, 0.0f, 1, 0, System.currentTimeMillis() - startTime);
      }
    }

    actions = orderActions(actions, board, player, null, depth);

    Action bestAction = null;
    float bestValue = Float.NEGATIVE_INFINITY;

    Accumulator rootAcc = null;
    if (nnueModel != null && board.rows == 12 && board.cols == 12) {
      rootAcc = new Accumulator();
      rootAcc.init(board, player, nnueModel);
    }

    float alpha = Float.NEGATIVE_INFINITY;
    float beta = Float.POSITIVE_INFINITY;
    boolean firstMove = true;
    for (Action action : actions) {
      Board child = applyAction(board, player, action);
      Accumulator childAcc = null;
      if (rootAcc != null) {
        childAcc = rootAcc.copy();
        Accumulator.computeDiff(board, child, childAcc, player, nnueModel);
      }

      // Negamax root: value of the move for `player` is the negation of the child's value
      // (which is relative to the opponent, the side to move at the child).
      float value;
      if (firstMove) {
        value =
            -alphaBeta(
                child, childAcc, depth - 1, -beta, -alpha, 3 - player, player, startTime,
                Long.MAX_VALUE);
        firstMove = false;
      } else {
        value =
            -alphaBeta(
                child, childAcc, depth - 1, -alpha - 1.0f, -alpha, 3 - player, player, startTime,
                Long.MAX_VALUE);
        if (value > alpha && value < beta) {
          value =
              -alphaBeta(
                  child, childAcc, depth - 1, -beta, -alpha, 3 - player, player, startTime,
                  Long.MAX_VALUE);
        }
      }

      if (value > bestValue) {
        bestValue = value;
        bestAction = action;
      }
      if (value > alpha) {
        alpha = value;
      }
    }

    long elapsedTime = System.currentTimeMillis() - startTime;
    return new SearchResult(bestAction, bestValue, depth, nodesEvaluated, elapsedTime);
  }

  public static SearchResult findBestActionWithTimeLimit(
      Board board, int player, long timeLimitMs, boolean canPlaceNeutral) {
    return new SearchEngine()
        .findBestActionWithTimeLimitUsingModel(board, player, timeLimitMs, canPlaceNeutral);
  }

  public SearchResult findBestActionWithTimeLimitUsingModel(
      Board board, int player, long timeLimitMs, boolean canPlaceNeutral) {
    long startTime = System.currentTimeMillis();
    nodesEvaluated = 0;
    Action globalBestAction = null;
    float globalBestScore = Float.NEGATIVE_INFINITY;
    int maxDepthReached = 0;

    if (!isCustomModel) {
      Action randomOpening = OpeningBook.getRandomOpeningMove(board, player, canPlaceNeutral);
      if (randomOpening != null) {
        return new SearchResult(randomOpening, 0.0f, 1, 0, System.currentTimeMillis() - startTime);
      }
    }

    // We get the legal actions once
    List<Action> actions = MoveGenerator.getLegalActions(player, board, canPlaceNeutral);
    if (actions.isEmpty()) {
      return new SearchResult(null, Float.NEGATIVE_INFINITY, 0, 0, 0);
    }

    // Fallback: simply pick the first available action in case depth 1 doesn't even finish
    globalBestAction = actions.get(0);

    Accumulator rootAcc = null;
    if (nnueModel != null && board.rows == 12 && board.cols == 12) {
      rootAcc = new Accumulator();
      rootAcc.init(board, player, nnueModel);
    }

    Action bestActionPreviousIteration = null;

    for (int depth = 1; depth <= 20; depth++) {
      try {
        Action bestActionAtDepth = null;
        float bestValueAtDepth = Float.NEGATIVE_INFINITY;

        actions = orderActions(actions, board, player, bestActionPreviousIteration, depth);

        float alpha = Float.NEGATIVE_INFINITY;
        float beta = Float.POSITIVE_INFINITY;
        boolean firstMove = true;
        for (Action action : actions) {
          Board child = applyAction(board, player, action);
          Accumulator childAcc = null;
          if (rootAcc != null) {
            childAcc = rootAcc.copy();
            Accumulator.computeDiff(board, child, childAcc, player, nnueModel);
          }

          // Negamax root: negate the child's (opponent-relative) value.
          float value;
          if (firstMove) {
            value =
                -alphaBeta(
                    child, childAcc, depth - 1, -beta, -alpha, 3 - player, player, startTime,
                    timeLimitMs);
            firstMove = false;
          } else {
            value =
                -alphaBeta(
                    child, childAcc, depth - 1, -alpha - 1.0f, -alpha, 3 - player, player, startTime,
                    timeLimitMs);
            if (value > alpha && value < beta) {
              value =
                  -alphaBeta(
                      child, childAcc, depth - 1, -beta, -alpha, 3 - player, player, startTime,
                      timeLimitMs);
            }
          }

          if (value > bestValueAtDepth) {
            bestValueAtDepth = value;
            bestActionAtDepth = action;
          }
          if (value > alpha) {
            alpha = value;
          }
        }
        // If we completed this depth without throwing SearchTimeoutException,
        // we can safely update the global best action.
        if (bestActionAtDepth != null) {
          globalBestAction = bestActionAtDepth;
          globalBestScore = bestValueAtDepth;
          maxDepthReached = depth;
          bestActionPreviousIteration = bestActionAtDepth;
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
