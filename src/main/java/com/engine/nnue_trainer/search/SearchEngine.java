package com.engine.nnue_trainer.search;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;

import java.util.ArrayList;
import java.util.List;

public class SearchEngine {

    /**
     * Executes alpha-beta minimax search.
     *
     * @param board The current state of the board.
     * @param depth The current search depth (starts at max and goes down to 0).
     * @param alpha The alpha value for pruning (best already explored option along path to root for maximizer).
     * @param beta The beta value for pruning (best already explored option along path to root for minimizer).
     * @param player The active player's ID (e.g. 1 or -1, or 1 or 2).
     * @param maximizingPlayer True if the current node is a maximizing node.
     * @return The evaluation score of the board.
     */
    public float alphaBeta(Board board, int depth, float alpha, float beta, int player, boolean maximizingPlayer) {
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
        return player == 1 ? 2 : 1;
    }

    /**
     * Stub for terminal check.
     */
    protected boolean isTerminal(Board board) {
        return false;
    }

    /**
     * Stub evaluation function: simple count of pieces owned by the player.
     * We evaluate from the perspective of the original maximizing player.
     */
    protected float evaluate(Board board, int player, boolean maximizingPlayer) {
        int originalPlayer = maximizingPlayer ? player : getOpponent(player);
        int opponent = getOpponent(originalPlayer);

        int myPieces = 0;
        int oppPieces = 0;

        for (int r = 0; r < board.rows; r++) {
            for (int c = 0; c < board.cols; c++) {
                Cell cell = board.getCell(r, c);
                if (cell != null && cell.kind != CellKind.EMPTY && cell.kind != CellKind.NEUTRAL) {
                    if (cell.owner == originalPlayer) {
                        myPieces++;
                    } else if (cell.owner == opponent) {
                        oppPieces++;
                    }
                }
            }
        }

        return myPieces - oppPieces;
    }

    /**
     * Stub for move generation. In a real game, this would generate all legal next board states.
     */
    protected List<Board> generateNextBoards(Board board, int player, boolean maximizingPlayer) {
        // Return empty list by default for stub
        return new ArrayList<>();
    }
}
