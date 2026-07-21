package com.engine.nnue_trainer.train;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.engine.nnue_trainer.nnue.NNUEModel;
import com.engine.nnue_trainer.search.SearchEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class PeriodicRetrainerTest {

  @TempDir Path tempDir;

  @Test
  public void rejectedCandidateLeavesLiveModelUnchanged() throws Exception {
    SearchEngine liveEngine = new SearchEngine();
    NNUEModel original = liveEngine.getNnueModel();
    PeriodicRetrainer retrainer =
        retrainer(
            liveEngine, (current, candidate) -> new PeriodicRetrainer.EvaluationResult(0, 1, 0));

    PeriodicRetrainer.RetrainingResult result = retrainer.retrainOnce();

    assertFalse(result.promoted());
    assertSame(original, liveEngine.getNnueModel());
    assertFalse(Files.exists(tempDir.resolve("out").resolve("nnue_weights.json")));
  }

  @Test
  public void promotedCandidateUpdatesLiveModelAndWritesVersionedArtifacts() throws Exception {
    SearchEngine liveEngine = new SearchEngine();
    NNUEModel original = liveEngine.getNnueModel();
    PeriodicRetrainer retrainer =
        retrainer(
            liveEngine, (current, candidate) -> new PeriodicRetrainer.EvaluationResult(1, 0, 0));

    PeriodicRetrainer.RetrainingResult result = retrainer.retrainOnce();

    assertTrue(result.promoted());
    assertNotSame(original, liveEngine.getNnueModel());
    assertTrue(Files.exists(tempDir.resolve("out").resolve("nnue_weights.json")));
    try (Stream<Path> artifacts = Files.list(tempDir.resolve("out"))) {
      assertTrue(
          artifacts.anyMatch(path -> path.getFileName().toString().startsWith("training-run-")));
    }
  }

  private PeriodicRetrainer retrainer(
      SearchEngine liveEngine, PeriodicRetrainer.CandidateEvaluator evaluator) throws Exception {
    Path db = tempDir.resolve("games.db");
    createDb(db);
    return new PeriodicRetrainer(
        liveEngine,
        new PeriodicRetrainer.Config(db, null, tempDir.resolve("out"), 1, 1, 1, 0.55, 3L),
        new GameImporter(),
        evaluator,
        Executors.newSingleThreadScheduledExecutor());
  }

  private static void createDb(Path db) throws Exception {
    String pgn = "[{\"player\":1,\"moves\":[{\"type\":\"place\",\"row\":0,\"col\":1}]}]";
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        Statement statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE games (id INTEGER PRIMARY KEY, result INTEGER, pgn_content TEXT, "
              + "rows INTEGER, cols INTEGER, player1_name TEXT, player2_name TEXT)");
      statement.execute(
          "INSERT INTO games (id, result, pgn_content, rows, cols, player1_name, player2_name) "
              + "VALUES (1, 1, '"
              + pgn
              + "', 12, 12, 'GoBot1', 'GoBot2')");
    }
  }
}
