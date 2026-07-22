import re

with open("src/test/java/com/engine/nnue_trainer/search/SearchEngineTest.java", "r") as f:
    text = f.read()

test_method = """
  @Test
  public void testTTDoesNotChangeBestMove() {
    Board board = new Board(5, 5);
    board.setCell(0, 0, new Cell(1, CellKind.BASE));
    board.setCell(4, 4, new Cell(2, CellKind.BASE));
    // Add some complexity to trigger interesting search trees
    board.setCell(1, 1, new Cell(1, CellKind.NORMAL));
    board.setCell(3, 3, new Cell(2, CellKind.NORMAL));

    // First search with TT enabled
    SearchEngine.USE_TT = true;
    SearchEngine engineTT = new SearchEngine();
    SearchResult resultTT = engineTT.findBestActionUsingModel(board, 1, 4, true);

    // Second search with TT disabled
    SearchEngine.USE_TT = false;
    SearchEngine engineNoTT = new SearchEngine();
    SearchResult resultNoTT = engineNoTT.findBestActionUsingModel(board, 1, 4, true);

    // Restore TT to default just in case
    SearchEngine.USE_TT = true;

    assertEquals(resultNoTT.bestAction, resultTT.bestAction, "Best move should be exactly the same regardless of TT usage");
    assertEquals(resultNoTT.score, resultTT.score, 0.001f, "Score should be exactly the same regardless of TT usage");
  }
"""

text = text.replace('public class SearchEngineTest {', 'public class SearchEngineTest {\n' + test_method)

with open("src/test/java/com/engine/nnue_trainer/search/SearchEngineTest.java", "w") as f:
    f.write(text)
