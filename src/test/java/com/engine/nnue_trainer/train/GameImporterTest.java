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

  @Test
  public void replayGameDiscountedLabelMode() throws Exception {
    String pgn =
        "["
            + "{\"player\":1,\"moves\":[{\"type\":\"place\",\"row\":0,\"col\":1}]},"
            + "{\"player\":2,\"moves\":[{\"type\":\"place\",\"row\":10,\"col\":10}]}"
            + "]";

    GameImporter.ImportOptions options =
        new GameImporter.ImportOptions(
            null, null, false, GameImporter.LabelMode.DISCOUNTED, 0.5, 0.9);

    List<NNUETrainer.TrainingExample> examples = new GameImporter().replayGame(pgn, 1, options);
    assertEquals(2, examples.size());

    // total turns = 2
    // turn 0: player 1, result 1 => outcome 1.0, distance = 1, target = 1.0 * 0.9^1 = 0.9
    assertEquals(0.9f, examples.get(0).target, 0.001f);

    // turn 1: player 2, result 1 => outcome -1.0, distance = 0, target = -1.0 * 0.9^0 = -1.0
    assertEquals(-1.0f, examples.get(1).target, 0.001f);
  }

  @Test
  public void replayGameTdLeafLabelMode() throws Exception {
    String pgn =
        "["
            + "{\"player\":1,\"moves\":[{\"type\":\"place\",\"row\":0,\"col\":1,\"score\":100.0}]},"
            + "{\"player\":2,\"moves\":[{\"type\":\"place\",\"row\":10,\"col\":10,\"score\":200.0}]}"
            + "]";

    GameImporter.ImportOptions options =
        new GameImporter.ImportOptions(
            null, null, false, GameImporter.LabelMode.TD_LEAF, 0.5, 0.98);

    List<NNUETrainer.TrainingExample> examples = new GameImporter().replayGame(pgn, 1, options);
    assertEquals(2, examples.size());

    // turn 0: player 1, result 1 => outcome 1.0, search_eval = 0.1, distance = 1
    // currentLambda = 0.5^1 = 0.5
    // target = (1 - 0.5) * 0.1 + 0.5 * 1.0 = 0.55
    assertEquals(0.55f, examples.get(0).target, 0.001f);

    // turn 1: player 2, result 1 => outcome -1.0, search_eval = 0.2, distance = 0
    // currentLambda = 0.5^0 = 1.0
    // target = (1 - 1.0) * 0.2 + 1.0 * (-1.0) = -1.0
    assertEquals(-1.0f, examples.get(1).target, 0.001f);
  }
}
