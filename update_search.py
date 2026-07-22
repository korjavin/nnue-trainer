import re

with open("src/main/java/com/engine/nnue_trainer/search/SearchEngine.java", "r") as f:
    text = f.read()

start_idx = text.find("  public float alphaBeta(")
end_idx = text.find("  public static Board applyAction")

new_code = """
  /**
   * Executes negamax search.
   *
   * @param board The current state of the board.
   * @param depth The current search depth (starts at max and goes down to 0).
   * @param alpha The alpha value for pruning (best already explored option along path to root).
   * @param beta The beta value for pruning (worst case scenario for the opponent).
   * @param player The active player's ID (e.g. 1 or 2).
   * @return The evaluation score of the board relative to the current player.
   */
  public float negamax(Board board, int depth, float alpha, float beta, int player) {
    return negamax(board, depth, alpha, beta, player, 0, Long.MAX_VALUE);
  }

  public float negamax(
      Board board,
      int depth,
      float alpha,
      float beta,
      int player,
      long startTime,
      long timeLimitMs) {
    return negamax(
        board, null, depth, alpha, beta, player, startTime, timeLimitMs);
  }

  public float negamax(
      Board board,
      Accumulator accumulator,
      int depth,
      float alpha,
      float beta,
      int player,
      long startTime,
      long timeLimitMs) {
    if (System.currentTimeMillis() - startTime >= timeLimitMs) {
      throw new SearchTimeoutException();
    }

    long zobristKey = Zobrist.computeHash(board, player);
    float alphaOrig = alpha;

    // TT Probe
    TTEntry tte = USE_TT ? tt.probe(zobristKey) : null;
    Action ttMove = null;
    if (tte != null && tte.depth >= depth) {
      if (tte.flag == TTEntry.FLAG_EXACT) {
        return tte.score;
      } else if (tte.flag == TTEntry.FLAG_LOWER_BOUND && tte.score >= beta) {
        return tte.score;
      } else if (tte.flag == TTEntry.FLAG_UPPER_BOUND && tte.score <= alpha) {
        return tte.score;
      }
      ttMove = tte.bestAction;
    }

    if (depth == 0 || isTerminal(board)) {
      if (USE_QUIESCENCE) {
        return quiescenceSearch(
            board, accumulator, alpha, beta, player, startTime, timeLimitMs);
      }
      return evaluate(board, accumulator, player);
    }

    List<Action> actions = MoveGenerator.getLegalActions(player, board, false);
    if (actions.isEmpty()) {
      return evaluate(board, accumulator, player);
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
        Accumulator.computeDiff(board, child, childAcc, player, nnueModel);
      }

      float eval;
      if (firstMove) {
        // Full window search for principal variation
        eval = -negamax(child, childAcc, depth - 1, -beta, -alpha, getOpponent(player), startTime, timeLimitMs);
        firstMove = false;
      } else {
        // Null window search
        eval = -negamax(child, childAcc, depth - 1, -alpha - 1, -alpha, getOpponent(player), startTime, timeLimitMs);
        if (eval > alpha && eval < beta) { // Re-search if it failed high
          eval = -negamax(child, childAcc, depth - 1, -beta, -alpha, getOpponent(player), startTime, timeLimitMs);
        }
      }

      if (eval > bestScore) {
        bestScore = eval;
        bestAction = action;
      }

      if (eval > alpha) {
        alpha = eval;
      }

      if (alpha >= beta) {
        if (!isCaptureOrThreat(board, player, action)) {
          killerMoves.addKiller(depth, action);
          historyTable.addBonus(player, action, depth);
        }
        break; // Beta cutoff (fail-high)
      }
    }

    if (USE_TT) {
      byte ttFlag;
      if (bestScore <= alphaOrig) {
        ttFlag = TTEntry.FLAG_UPPER_BOUND; // Fail-low
      } else if (bestScore >= beta) {
        ttFlag = TTEntry.FLAG_LOWER_BOUND; // Fail-high
      } else {
        ttFlag = TTEntry.FLAG_EXACT;
      }
      tt.store(zobristKey, bestAction, bestScore, depth, ttFlag);
    }

    return bestScore;
  }

  public float quiescenceSearch(
      Board board,
      Accumulator accumulator,
      float alpha,
      float beta,
      int player,
      long startTime,
      long timeLimitMs) {
    return quiescenceSearch(
        board, accumulator, alpha, beta, player, startTime, timeLimitMs, 0);
  }

  public float quiescenceSearch(
      Board board,
      Accumulator accumulator,
      float alpha,
      float beta,
      int player,
      long startTime,
      long timeLimitMs,
      int qsDepth) {

    if (qsDepth >= 6 || System.currentTimeMillis() - startTime >= timeLimitMs) {
      return evaluate(board, accumulator, player);
    }

    float standPat = evaluate(board, accumulator, player);

    if (standPat >= beta) return beta;
    if (alpha < standPat) alpha = standPat;

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

    float maxEval = standPat;
    for (Action action : loudActions) {
      Board child = applyAction(board, player, action);
      Accumulator childAcc = null;
      if (accumulator != null) {
        childAcc = accumulator.copy();
        Accumulator.computeDiff(board, child, childAcc, player, nnueModel);
      }

      float eval = -quiescenceSearch(
          child,
          childAcc,
          -beta,
          -alpha,
          getOpponent(player),
          startTime,
          timeLimitMs,
          qsDepth + 1);

      maxEval = Math.max(maxEval, eval);
      alpha = Math.max(alpha, eval);
      if (alpha >= beta) break;
    }
    return maxEval;
  }

  protected float evaluate(Board board, int player) {
    return evaluate(board, null, player);
  }

  /** Evaluation function: simple count of pieces owned by the player, penalized if base is lost. */
  protected float evaluate(
      Board board, Accumulator accumulator, int player) {
    nodesEvaluated++;
    int opponent = getOpponent(player);

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
            if (cell.owner == player) myBaseAlive = true;
            else if (cell.owner == opponent) oppBaseAlive = true;
          }
          if (cell.kind != CellKind.EMPTY && cell.kind != CellKind.NEUTRAL) {
            if (cell.owner == player) {
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
        float[] features = BoardFeatureMapper.map(board, player);
        return nnueModel.forward(features);
      }
    }

    return myPieces - oppPieces;
  }

"""

new_text = text[:start_idx] + new_code + text[end_idx:]

with open("src/main/java/com/engine/nnue_trainer/search/SearchEngine.java", "w") as f:
    f.write(new_text)
