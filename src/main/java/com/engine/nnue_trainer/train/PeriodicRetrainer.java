package com.engine.nnue_trainer.train;

import com.engine.nnue_trainer.board.Action;
import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.board.PlaceNeutralsAction;
import com.engine.nnue_trainer.nnue.NNUEModel;
import com.engine.nnue_trainer.nnue.NNUETrainer;
import com.engine.nnue_trainer.search.SearchEngine;
import com.engine.nnue_trainer.search.SearchResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class PeriodicRetrainer implements AutoCloseable {
  private final SearchEngine liveSearchEngine;
  private final Config config;
  private final GameImporter importer;
  private final CandidateEvaluator evaluator;
  private final ScheduledExecutorService scheduler;

  public static PeriodicRetrainer createDefault(SearchEngine liveSearchEngine, Config config) {
    return new PeriodicRetrainer(
        liveSearchEngine,
        config,
        new GameImporter(),
        null,
        Executors.newSingleThreadScheduledExecutor());
  }

  public PeriodicRetrainer(
      SearchEngine liveSearchEngine,
      Config config,
      GameImporter importer,
      CandidateEvaluator evaluator,
      ScheduledExecutorService scheduler) {
    this.liveSearchEngine = liveSearchEngine;
    this.config = config;
    this.importer = importer;
    this.evaluator = evaluator == null ? this::runGauntlet : evaluator;
    this.scheduler = scheduler;
  }

  public void start(Duration initialDelay, Duration period) {
    scheduler.scheduleAtFixedRate(
        this::runOnceSafely, initialDelay.toMillis(), period.toMillis(), TimeUnit.MILLISECONDS);
  }

  public RetrainingResult retrainOnce() throws SQLException, IOException {
    GameImporter.ImportResult importResult =
        importer.importGames(
            new GameImporter.ImportOptions(
                config.dbPath(),
                config.minStartedAt(),
                true,
                config.labelMode() != null ? config.labelMode() : GameImporter.LabelMode.OUTCOME,
                config.labelMode() != null ? config.lambdaVal() : 0.5,
                config.labelMode() != null ? config.gammaVal() : 0.98));
    if (importResult.examples().size() < config.minExamples()) {
      return RetrainingResult.skipped(
          importResult.examples().size(), "not enough examples for retraining");
    }

    NNUETrainer trainer = new NNUETrainer(config.seed());
    NNUETrainer.TrainingResult trainingResult = trainer.train(importResult.examples());
    NNUEModel candidate = trainer.createModel();
    NNUEModel current = liveSearchEngine.getNnueModel();
    EvaluationResult evaluation = evaluator.evaluate(current, candidate);

    if (!evaluation.promoted(config.promotionWinRate())) {
      return new RetrainingResult(
          false,
          false,
          importResult.examples().size(),
          trainingResult.finalMse(),
          evaluation,
          "candidate rejected by gauntlet");
    }

    Files.createDirectories(config.outputDir());
    String runId = Instant.now().toString().replace(':', '-');
    Path versionedWeights = config.outputDir().resolve("nnue_weights-" + runId + ".json");
    Path latestWeights = config.outputDir().resolve("nnue_weights.json");
    trainer.saveWeights(versionedWeights);
    trainer.saveWeights(latestWeights);
    writeMetadata(runId, importResult, trainingResult, evaluation, versionedWeights);
    liveSearchEngine.setNnueModel(candidate);

    return new RetrainingResult(
        true, true, importResult.examples().size(), trainingResult.finalMse(), evaluation, runId);
  }

  private void runOnceSafely() {
    try {
      RetrainingResult result = retrainOnce();
      System.out.println("NNUE retraining result: " + result.message());
    } catch (Exception e) {
      System.err.println("NNUE retraining failed: " + e.getMessage());
      e.printStackTrace();
    }
  }

  private EvaluationResult runGauntlet(NNUEModel current, NNUEModel candidate) {
    SearchEngine candidateEngine = new SearchEngine(candidate);
    SearchEngine currentEngine = new SearchEngine(current);
    int candidateWins = 0;
    int currentWins = 0;
    int draws = 0;

    for (int game = 0; game < config.gauntletGames(); game++) {
      boolean candidateIsPlayerOne = game % 2 == 0;
      int winner =
          playGame(
              candidateIsPlayerOne ? candidateEngine : currentEngine,
              candidateIsPlayerOne ? currentEngine : candidateEngine);
      if (winner == 0) {
        draws++;
      } else if ((winner == 1 && candidateIsPlayerOne) || (winner == 2 && !candidateIsPlayerOne)) {
        candidateWins++;
      } else {
        currentWins++;
      }
    }

    return new EvaluationResult(candidateWins, currentWins, draws);
  }

  private int playGame(SearchEngine playerOneEngine, SearchEngine playerTwoEngine) {
    Board board = new Board(12, 12);
    board.setCell(0, 0, new Cell(1, CellKind.BASE));
    board.setCell(11, 11, new Cell(2, CellKind.BASE));
    boolean[] neutralUsed = new boolean[3];

    int currentPlayer = 1;
    for (int turn = 0; turn < 200; turn++) {
      SearchEngine engine = currentPlayer == 1 ? playerOneEngine : playerTwoEngine;
      for (int actionIndex = 0; actionIndex < 3; actionIndex++) {
        if (engine.isTerminal(board)) {
          return winner(board);
        }

        boolean canPlaceNeutral = actionIndex == 0 && !neutralUsed[currentPlayer];
        SearchResult result =
            engine.findBestActionUsingModel(
                board, currentPlayer, config.searchDepth(), canPlaceNeutral);
        Action action = result.bestAction;
        if (action == null) {
          return 3 - currentPlayer;
        }

        if (action instanceof PlaceNeutralsAction) {
          neutralUsed[currentPlayer] = true;
          board = SearchEngine.applyAction(board, currentPlayer, action);
          break;
        }
        board = SearchEngine.applyAction(board, currentPlayer, action);
      }
      currentPlayer = 3 - currentPlayer;
    }
    return 0;
  }

  private static int winner(Board board) {
    boolean playerOneBase = false;
    boolean playerTwoBase = false;
    for (int row = 0; row < board.rows; row++) {
      for (int col = 0; col < board.cols; col++) {
        Cell cell = board.getCell(row, col);
        if (cell.kind == CellKind.BASE) {
          if (cell.owner == 1) {
            playerOneBase = true;
          } else if (cell.owner == 2) {
            playerTwoBase = true;
          }
        }
      }
    }
    if (playerOneBase && !playerTwoBase) {
      return 1;
    }
    if (!playerOneBase && playerTwoBase) {
      return 2;
    }
    return 0;
  }

  private void writeMetadata(
      String runId,
      GameImporter.ImportResult importResult,
      NNUETrainer.TrainingResult trainingResult,
      EvaluationResult evaluation,
      Path weightsPath)
      throws IOException {
    Metadata metadata = new Metadata();
    metadata.runId = runId;
    metadata.seed = config.seed();
    metadata.dbPath = config.dbPath().toString();
    metadata.minStartedAt = config.minStartedAt();
    metadata.labelMode = config.labelMode();
    metadata.lambdaVal = config.lambdaVal();
    metadata.gammaVal = config.gammaVal();
    metadata.examples = importResult.examples().size();
    metadata.importedGames = importResult.importedGames();
    metadata.skippedDuplicateGames = importResult.skippedDuplicates();
    metadata.finalMse = trainingResult.finalMse();
    metadata.candidateWins = evaluation.candidateWins();
    metadata.currentWins = evaluation.currentWins();
    metadata.draws = evaluation.draws();
    metadata.promotionWinRate = config.promotionWinRate();
    metadata.weightsPath = weightsPath.toString();
    new ObjectMapper()
        .writerWithDefaultPrettyPrinter()
        .writeValue(
            config.outputDir().resolve("training-run-" + runId + ".json").toFile(), metadata);
  }

  @Override
  public void close() {
    scheduler.shutdownNow();
  }

  public interface CandidateEvaluator {
    EvaluationResult evaluate(NNUEModel current, NNUEModel candidate);
  }

  public record Config(
      Path dbPath,
      String minStartedAt,
      Path outputDir,
      int minExamples,
      int gauntletGames,
      int searchDepth,
      double promotionWinRate,
      long seed,
      GameImporter.LabelMode labelMode,
      double lambdaVal,
      double gammaVal) {}

  public record EvaluationResult(int candidateWins, int currentWins, int draws) {
    public boolean promoted(double winRateThreshold) {
      int decisive = candidateWins + currentWins;
      return decisive > 0 && ((double) candidateWins / decisive) >= winRateThreshold;
    }
  }

  public record RetrainingResult(
      boolean attempted,
      boolean promoted,
      int examples,
      float finalMse,
      EvaluationResult evaluation,
      String message) {
    public static RetrainingResult skipped(int examples, String message) {
      return new RetrainingResult(false, false, examples, Float.NaN, null, message);
    }
  }

  public static class Metadata {
    public String runId;
    public long seed;
    public String dbPath;
    public String minStartedAt;
    public GameImporter.LabelMode labelMode;
    public double lambdaVal;
    public double gammaVal;
    public int examples;
    public int importedGames;
    public int skippedDuplicateGames;
    public float finalMse;
    public int candidateWins;
    public int currentWins;
    public int draws;
    public double promotionWinRate;
    public String weightsPath;
  }
}
