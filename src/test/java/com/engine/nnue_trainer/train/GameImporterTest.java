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
  public void replayGameHandlesNeutralsCleanupAndDrawLabels() throws Exception {
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
    assertEquals(1.0f, afterNeutrals.features[(0 * 12 + 3) * 6]);
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
