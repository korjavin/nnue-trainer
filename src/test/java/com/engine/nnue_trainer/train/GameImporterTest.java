package com.engine.nnue_trainer.train;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.engine.nnue_trainer.nnue.NNUETrainer;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class GameImporterTest {

  @TempDir Path tempDir;

  @Test
  public void replayGameUsesCanonicalRulesAndDrawLabels() throws Exception {
    String pgn =
        "["
            + "{\"player\":1,\"moves\":["
            + "{\"type\":\"place\",\"row\":0,\"col\":1},"
            + "{\"type\":\"place\",\"row\":0,\"col\":2},"
            + "{\"type\":\"place\",\"row\":0,\"col\":3}]},"
            + "{\"player\":2,\"moves\":[{\"type\":\"place\",\"row\":10,\"col\":10}]},"
            + "{\"player\":1,\"moves\":[{\"type\":\"neutrals\",\"cells\":["
            + "{\"row\":0,\"col\":1},{\"row\":0,\"col\":2}]}]}"
            + "]";

    List<NNUETrainer.TrainingExample> examples = new GameImporter().replayGame(pgn, 0);

    assertEquals(3, examples.size());
    NNUETrainer.TrainingExample afterNeutrals = examples.get(2);
    assertEquals(0.0f, afterNeutrals.target);
    assertEquals(1.0f, afterNeutrals.features[(0 * 12 + 1) * 6 + 5]);
    assertEquals(1.0f, afterNeutrals.features[(0 * 12 + 2) * 6 + 5]);
    // (0,3) lost base-connectivity when (0,1)/(0,2) turned neutral. The real rules KEEP such a
    // cell (plane 1 = own NORMAL); the old SearchEngine.applyAction replay erased it (plane 0),
    // feeding the retrainer boards the recorded game never contained.
    assertEquals(1.0f, afterNeutrals.features[(0 * 12 + 3) * 6 + 1]);
  }

  /**
   * The retrainer trains on whatever this replays, so a turn the rules cannot produce (here: a
   * fourth action, and a neutral pair placed mid-turn) must drop the game rather than fabricate a
   * board. Rebuilding the state per move — with {@code movesLeft} reset to 3 — replayed both as
   * legal.
   */
  @Test
  public void replayGameSkipsGamesWithOutOfRulesTurns() throws Exception {
    String fourActions =
        "[{\"player\":1,\"moves\":["
            + "{\"type\":\"place\",\"row\":0,\"col\":1},"
            + "{\"type\":\"place\",\"row\":0,\"col\":2},"
            + "{\"type\":\"place\",\"row\":0,\"col\":3},"
            + "{\"type\":\"place\",\"row\":0,\"col\":4}]}]";
    assertEquals(List.of(), new GameImporter().replayGame(fourActions, 1));

    String midTurnNeutral =
        "[{\"player\":1,\"moves\":["
            + "{\"type\":\"place\",\"row\":0,\"col\":1},"
            + "{\"type\":\"neutrals\",\"cells\":[{\"row\":0,\"col\":1},{\"row\":1,\"col\":0}]}]}]";
    assertEquals(List.of(), new GameImporter().replayGame(midTurnNeutral, 1));

    // Ineligible target: (5,5) touches nothing connected to p1's base.
    String disconnected = "[{\"player\":1,\"moves\":[{\"type\":\"place\",\"row\":5,\"col\":5}]}]";
    assertEquals(List.of(), new GameImporter().replayGame(disconnected, 1));
  }

  @Test
  public void importGamesFailsForMissingDatabase() {
    Path missing = tempDir.resolve("missing.db");
    assertThrows(Exception.class, () -> new GameImporter().importGames(missing));
  }

  @Test
  public void importGamesDeduplicatesPgnRows() throws Exception {
    Path db = tempDir.resolve("games.db");
    String pgn = "[{\"player\":1,\"moves\":[{\"type\":\"place\",\"row\":0,\"col\":1}]}]";
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        Statement statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE games (id INTEGER PRIMARY KEY, result INTEGER, pgn_content TEXT, "
              + "rows INTEGER, cols INTEGER, player1_name TEXT, player2_name TEXT)");
      for (int id = 1; id <= 2; id++) {
        statement.execute(
            "INSERT INTO games (id, result, pgn_content, rows, cols, player1_name, player2_name) "
                + "VALUES ("
                + id
                + ", 1, '"
                + pgn
                + "', 12, 12, 'GoBot1', 'GoBot2')");
      }
    }

    GameImporter.ImportResult result = new GameImporter().importGames(db);

    assertEquals(1, result.importedGames());
    assertEquals(1, result.skippedDuplicates());
    assertEquals(1, result.examples().size());
  }
}
