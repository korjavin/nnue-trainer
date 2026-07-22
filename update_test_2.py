import re

with open("src/test/java/com/engine/nnue_trainer/search/SearchEngineTest.java", "r") as f:
    text = f.read()

replacement = """
  @Test
  public void testTTDoesNotChangeBestMove() {
    Board board = new Board(5, 5);
    board.setCell(0, 0, new Cell(1, CellKind.BASE));
    board.setCell(4, 4, new Cell(2, CellKind.BASE));
    // Setup a very clear threat where player 1 has exactly one winning move (capture base).
    board.setCell(3, 4, new Cell(1, CellKind.NORMAL));

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

    assertEquals(resultNoTT.score, resultTT.score, 0.001f, "Score should be exactly the same regardless of TT usage");
    assertEquals(resultNoTT.bestAction, resultTT.bestAction, "Best move should be exactly the same regardless of TT usage");
  }
"""

text = re.sub(r'  @Test\n  public void testTTDoesNotChangeBestMove\(\) \{.*?(?=\n  @Test|\n\})', replacement.strip() + '\n', text, flags=re.DOTALL)

with open("src/test/java/com/engine/nnue_trainer/search/SearchEngineTest.java", "w") as f:
    f.write(text)
