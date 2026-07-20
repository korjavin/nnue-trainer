package com.engine.nnue_trainer.search;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class SearchEngineTest {

  static class MockSearchEngine extends SearchEngine {
    private final Map<Board, List<Board>> childrenMap = new HashMap<>();
    private final Map<Board, Float> evaluationMap = new HashMap<>();
    public int nodesEvaluated = 0;

    public void addChild(Board parent, Board child) {
      childrenMap.computeIfAbsent(parent, k -> new ArrayList<>()).add(child);
    }

    public void setEvaluation(Board board, float eval) {
      evaluationMap.put(board, eval);
    }

    @Override
    protected List<Board> generateNextBoards(Board board, int player, boolean maximizingPlayer) {
      return childrenMap.getOrDefault(board, new ArrayList<>());
    }

    @Override
    protected float evaluate(Board board, int player, boolean maximizingPlayer) {
      nodesEvaluated++;
      if (evaluationMap.containsKey(board)) {
        return evaluationMap.get(board);
      }
      return super.evaluate(board, player, maximizingPlayer);
    }
  }

  @Test
  public void testAlphaBeta_simpleMinimaxDecision() {
    MockSearchEngine engine = new MockSearchEngine();

    Board root = new Board(3, 3);
    Board child1 = new Board(3, 3);
    Board child2 = new Board(3, 3);

    // Maximizing player (player 1) should choose child2 because it leads to a better score.
    engine.addChild(root, child1);
    engine.addChild(root, child2);

    // Terminal states for children
    engine.setEvaluation(child1, 10.0f);
    engine.setEvaluation(child2, 20.0f);

    float bestScore =
        engine.alphaBeta(root, 1, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, 1, true);

    assertEquals(20.0f, bestScore);
  }

  @Test
  public void testAlphaBeta_pruning() {
    MockSearchEngine engine = new MockSearchEngine();

    Board root = new Board(3, 3);

    Board child1 = new Board(3, 3);
    Board child2 = new Board(3, 3);

    Board child1_1 = new Board(3, 3); // Eval: 3
    Board child1_2 = new Board(3, 3); // Eval: 5

    Board child2_1 = new Board(3, 3); // Eval: 2
    Board child2_2 = new Board(3, 3); // Eval: 9

    engine.addChild(root, child1);
    engine.addChild(root, child2);

    engine.addChild(child1, child1_1);
    engine.addChild(child1, child1_2);

    engine.addChild(child2, child2_1);
    engine.addChild(child2, child2_2);

    engine.setEvaluation(child1_1, 3.0f);
    engine.setEvaluation(child1_2, 5.0f);
    engine.setEvaluation(child2_1, 2.0f);
    engine.setEvaluation(
        child2_2, 9.0f); // This node should not be evaluated due to alpha-beta pruning

    // Depth 2. Root is maximizer. Children 1 & 2 are minimizers.
    // child1 minimizer picks 3. (beta = 3)
    // root maximizer gets alpha = 3.
    // child2 minimizer examines child2_1 which is 2. (beta = 2).
    // 2 <= 3 (beta <= alpha), so child2 prunes child2_2.

    float bestScore =
        engine.alphaBeta(root, 2, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, 1, true);

    // The best score root can get is 3 (by choosing child1, where opponent will minimize it to 3).
    assertEquals(3.0f, bestScore);

    // 3 leaves should be evaluated: child1_1, child1_2, child2_1.
    // child2_2 is pruned.
    assertEquals(3, engine.nodesEvaluated);
  }

  @Test
  public void testEvaluate_pieceCount() {
    SearchEngine engine = new SearchEngine();
    Board board = new Board(2, 2);

    board.setCell(0, 0, new Cell(1, CellKind.NORMAL));
    board.setCell(0, 1, new Cell(1, CellKind.NORMAL));
    board.setCell(1, 0, new Cell(2, CellKind.NORMAL));
    // player 1 has 2 pieces, player 2 has 1 piece

    // player 1 is maximizing
    float score = engine.evaluate(board, 1, true);
    assertEquals(1.0f, score); // 2 - 1 = 1

    // player 2 is maximizing
    float score2 = engine.evaluate(board, 2, true);
    assertEquals(-1.0f, score2); // 1 - 2 = -1
  }
}
