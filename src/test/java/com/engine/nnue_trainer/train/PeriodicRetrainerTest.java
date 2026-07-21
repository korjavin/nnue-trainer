package com.engine.nnue_trainer.train;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.engine.nnue_trainer.search.SearchEngine;
import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

public class PeriodicRetrainerTest {

  @Test
  public void testRetrainAndEvaluate() throws Exception {
    // Create a temporary SQLite database with a mock game
    File tempDb = File.createTempFile("test_retrain_games", ".db");
    tempDb.deleteOnExit();

    String url = "jdbc:sqlite:" + tempDb.getAbsolutePath();
    try (Connection conn = DriverManager.getConnection(url);
        Statement stmt = conn.createStatement()) {

      stmt.execute(
          "CREATE TABLE games (id INTEGER PRIMARY KEY, result INTEGER, pgn_content TEXT, rows INTEGER, cols INTEGER, player1_name TEXT, player2_name TEXT)");

      // Add a simple game
      String pgn = "[{\"player\": 1, \"moves\": [{\"type\": \"place\", \"row\": 0, \"col\": 1}]}]";
      stmt.execute(
          "INSERT INTO games (result, pgn_content, rows, cols, player1_name, player2_name) VALUES (1, '"
              + pgn
              + "', 12, 12, 'GoBot1', 'GoBot2')");
    }

    File weightsOut = File.createTempFile("nnue_weights", ".json");
    weightsOut.deleteOnExit();

    SearchEngine liveEngine = new SearchEngine();

    // Use 1 gauntlet game and 0.0 threshold to ensure it promotes and tests the logic
    PeriodicRetrainer retrainer =
        new PeriodicRetrainer(
            liveEngine, tempDb.getAbsolutePath(), weightsOut.getAbsolutePath(), 1, 0.0, 42L);

    retrainer.retrainAndEvaluate();

    // Assert that weights were exported
    assertTrue(weightsOut.exists());
    assertTrue(weightsOut.length() > 0);
  }
}
