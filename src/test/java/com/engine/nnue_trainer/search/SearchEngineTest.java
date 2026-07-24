package com.engine.nnue_trainer.search;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.engine.nnue_trainer.board.Action;
import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.board.MoveAction;
import com.engine.nnue_trainer.board.Pos;
import com.engine.nnue_trainer.nnue.NNUEModel;
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
    protected float evaluate(Board board, int player, boolean maximizingPlayer) {
      nodesEvaluated++;
      if (evaluationMap.containsKey(board)) {
        return evaluationMap.get(board);
      }
      return super.evaluate(board, player, maximizingPlayer);
    }

    @Override
    protected float evaluate(
        Board board,
        com.engine.nnue_trainer.nnue.Accumulator accumulator,
        int player,
        boolean maximizingPlayer) {
      nodesEvaluated++;
      if (evaluationMap.containsKey(board)) {
        return evaluationMap.get(board);
      }
      return super.evaluate(board, accumulator, player, maximizingPlayer);
    }
  }

  @Test
  public void testFindBestActionWithTimeLimit_timeout() {
    Board board = new Board(3, 3);
    // Set up a simple non-terminal board
    board.setCell(0, 0, new Cell(1, CellKind.BASE));
    board.setCell(2, 2, new Cell(2, CellKind.BASE));
    board.setCell(0, 1, new Cell(1, CellKind.NORMAL));
    board.setCell(2, 1, new Cell(2, CellKind.NORMAL));

    // With 1ms limit, it should timeout very quickly but return a valid move safely.
    SearchResult result = SearchEngine.findBestActionWithTimeLimit(board, 1, 1, false);

    // Just verify that we got a legal action and it didn't throw an exception or crash
    org.junit.jupiter.api.Assertions.assertNotNull(result.bestAction);
  }

  @Test
  public void testFindBestActionUsesInjectedModel() {
    Board board = new Board(12, 12);
    board.setCell(0, 0, new Cell(1, CellKind.BASE));
    board.setCell(11, 11, new Cell(2, CellKind.BASE));

    NNUEModel model = modelFavoringOwnNormalAt(1, 1);
    SearchEngine engine = new SearchEngine(model);

    Action action = engine.findBestActionUsingModel(board, 1, 1, false).bestAction;

    assertEquals(new MoveAction(new Pos(1, 1)), action);
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

  @Test
  public void testMoveOrdering() {
    SearchEngine engine = new SearchEngine();
    Board board = new Board(5, 5);

    // Player 1 is playing, opponent is Player 2
    int player = 1;

    // Set up opponent base
    board.setCell(4, 4, new Cell(2, CellKind.BASE));

    // Set up opponent normal cell
    board.setCell(2, 2, new Cell(2, CellKind.NORMAL));

    // Create a list of actions to test
    List<Action> actions = new ArrayList<>();

    Action neutralMove = new MoveAction(new Pos(0, 0)); // Empty space (Score 0)
    Action adjacentToBase = new MoveAction(new Pos(3, 4)); // Adjacent to base (Score 100)
    Action captureNormal = new MoveAction(new Pos(2, 2)); // Capture normal cell (Score 1000)
    Action captureBase = new MoveAction(new Pos(4, 4)); // Capture base (Score 10000)

    actions.add(neutralMove);
    actions.add(captureNormal);
    actions.add(adjacentToBase);
    actions.add(captureBase);

    // Order actions
    List<Action> orderedActions = engine.orderActions(actions, board, player);

    // Assert correct order
    assertEquals(4, orderedActions.size());
    assertEquals(captureBase, orderedActions.get(0));
    assertEquals(captureNormal, orderedActions.get(1));
    assertEquals(adjacentToBase, orderedActions.get(2));
    assertEquals(neutralMove, orderedActions.get(3));
  }

  private static NNUEModel modelFavoringOwnNormalAt(int row, int col) {
    float[][] hiddenWeights = new float[1][864];
    float[] hiddenBiases = new float[1];
    float[] outputWeights = new float[] {10.0f};
    int featureIndex = (row * 12 + col) * 6 + 1;
    hiddenWeights[0][featureIndex] = 1.0f;
    return new NNUEModel(hiddenWeights, hiddenBiases, outputWeights, 0.0f);
  }
}
