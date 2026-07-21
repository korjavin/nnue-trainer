package com.engine.nnue_trainer.train;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class GameImporterTest {

  private static final String DB_PATH = "target/test_games.db";

  @BeforeEach
  public void setUp() throws Exception {
    File dbFile = new File(DB_PATH);
    if (dbFile.exists()) {
      dbFile.delete();
    }

    // Create mock sqlite database with one game
    try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH);
        Statement stmt = conn.createStatement()) {
      stmt.execute(
          "CREATE TABLE games (id INTEGER PRIMARY KEY, result INTEGER, rows INTEGER, cols INTEGER, pgn_content TEXT, player1_name TEXT, player2_name TEXT)");

      String mockPgn = "[{\"player\":1,\"moves\":[{\"type\":\"place\",\"row\":0,\"col\":1}]}]";
      String insert =
          String.format(
              "INSERT INTO games (id, result, rows, cols, pgn_content, player1_name, player2_name) VALUES (1, 1, 12, 12, '%s', 'GoBot1', 'GoBot2')",
              mockPgn);
      stmt.execute(insert);
    }
  }

  @AfterEach
  public void tearDown() {
    File dbFile = new File(DB_PATH);
    if (dbFile.exists()) {
      dbFile.delete();
    }
  }

  @Test
  public void testImportGames() {
    GameImporter importer = new GameImporter();
    List<GameImporter.TrainingPair> dataset = importer.importGames(DB_PATH);

    assertNotNull(dataset);
    assertEquals(1, dataset.size());

    GameImporter.TrainingPair pair = dataset.get(0);
    assertNotNull(pair.features);
    assertEquals(864, pair.features.length);
    assertEquals(1.0f, pair.target);
  }
}
