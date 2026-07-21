package com.engine.nnue_trainer.train;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.engine.nnue_trainer.nnue.NNUETrainer;
import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;

public class GameImporterTest {

  @Test
  public void testImportGames() throws Exception {
    // Create a temporary SQLite database
    File tempDb = File.createTempFile("test_games", ".db");
    tempDb.deleteOnExit();

    String url = "jdbc:sqlite:" + tempDb.getAbsolutePath();
    try (Connection conn = DriverManager.getConnection(url);
        Statement stmt = conn.createStatement()) {

      stmt.execute(
          "CREATE TABLE games (id INTEGER PRIMARY KEY, result INTEGER, pgn_content TEXT, rows INTEGER, cols INTEGER, player1_name TEXT, player2_name TEXT)");

      String pgn = "[{\"player\": 1, \"moves\": [{\"type\": \"place\", \"row\": 0, \"col\": 1}]}]";
      stmt.execute(
          "INSERT INTO games (result, pgn_content, rows, cols, player1_name, player2_name) VALUES (1, '"
              + pgn
              + "', 12, 12, 'GoBot1', 'GoBot2')");
    }

    List<NNUETrainer.TrainingExample> dataset = GameImporter.importGames(tempDb.getAbsolutePath());

    assertEquals(1, dataset.size());
    assertEquals(1.0f, dataset.get(0).target);

    // Active player was 1, so row 0 col 1 (index 1) should belong to player 1
    // Normal cell owner 1 = stateIndex 1. Feature index = 1 * 6 + 1 = 7
    assertEquals(1.0f, dataset.get(0).features[7]);
  }
}
