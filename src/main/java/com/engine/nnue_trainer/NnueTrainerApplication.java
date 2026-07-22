package com.engine.nnue_trainer;

import com.engine.nnue_trainer.protocol.BotWebSocketClient;
import com.engine.nnue_trainer.search.SearchEngine;
import com.engine.nnue_trainer.train.PeriodicRetrainer;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class NnueTrainerApplication {

  public static void main(String[] args) {
    SpringApplication.run(NnueTrainerApplication.class, args);

    try {
      String wsUrl = System.getenv("BACKEND_URL");
      if (wsUrl == null || wsUrl.isEmpty()) {
        wsUrl = "ws://localhost:8080/ws";
      }
      System.out.println("Starting BotWebSocketClient connecting to " + wsUrl);
      SearchEngine liveSearchEngine = new SearchEngine();
      BotWebSocketClient client = new BotWebSocketClient(new URI(wsUrl), liveSearchEngine);
      client.connect();
      startRetrainingIfEnabled(liveSearchEngine);
    } catch (Exception e) {
      System.err.println("Failed to start bot client: " + e.getMessage());
      e.printStackTrace();
    }
  }

  private static void startRetrainingIfEnabled(SearchEngine liveSearchEngine) {
    if (!Boolean.parseBoolean(System.getenv().getOrDefault("NNUE_RETRAINING_ENABLED", "false"))) {
      return;
    }

    Path dbPath =
        Path.of(
            System.getenv().getOrDefault("NNUE_GAMES_DB", "../virusgame/backend/data/games.db"));
    Path outputDir =
        Path.of(System.getenv().getOrDefault("NNUE_RETRAINING_OUTPUT_DIR", "target/nnue-training"));
    String minStartedAt = System.getenv("NNUE_GAMES_MIN_STARTED_AT");
    int minExamples = Integer.parseInt(System.getenv().getOrDefault("NNUE_MIN_EXAMPLES", "512"));
    int gauntletGames = Integer.parseInt(System.getenv().getOrDefault("NNUE_GAUNTLET_GAMES", "20"));
    int searchDepth = Integer.parseInt(System.getenv().getOrDefault("NNUE_GAUNTLET_DEPTH", "2"));
    double promotionWinRate =
        Double.parseDouble(System.getenv().getOrDefault("NNUE_PROMOTION_WIN_RATE", "0.55"));
    long seed = Long.parseLong(System.getenv().getOrDefault("NNUE_TRAINING_SEED", "0"));

    PeriodicRetrainer retrainer =
        PeriodicRetrainer.createDefault(
            liveSearchEngine,
            new PeriodicRetrainer.Config(
                dbPath,
                minStartedAt,
                outputDir,
                minExamples,
                gauntletGames,
                searchDepth,
                promotionWinRate,
                seed,
                com.engine.nnue_trainer.train.GameImporter.LabelMode.OUTCOME,
                0.5,
                0.98));
    retrainer.start(Duration.ofMinutes(1), Duration.ofHours(24));
  }
}
