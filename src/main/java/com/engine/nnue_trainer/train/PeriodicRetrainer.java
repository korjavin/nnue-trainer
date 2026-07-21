package com.engine.nnue_trainer.train;

import com.engine.nnue_trainer.board.Action;
import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.board.MoveGenerator;
import com.engine.nnue_trainer.nnue.NNUEModel;
import com.engine.nnue_trainer.nnue.NNUETrainer;
import com.engine.nnue_trainer.search.SearchEngine;
import com.engine.nnue_trainer.search.SearchResult;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class PeriodicRetrainer {

  private static final Logger logger = Logger.getLogger(PeriodicRetrainer.class.getName());

  private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
  private final SearchEngine liveSearchEngine;
  private final String dbPath;
  private final String weightsOutputPath;
  private final int gauntletGames;
  private final double winRateThreshold;
  private final long seed;

  public PeriodicRetrainer(
      SearchEngine liveSearchEngine,
      String dbPath,
      String weightsOutputPath,
      int gauntletGames,
      double winRateThreshold,
      long seed) {
    this.liveSearchEngine = liveSearchEngine;
    this.dbPath = dbPath;
    this.weightsOutputPath = weightsOutputPath;
    this.gauntletGames = gauntletGames;
    this.winRateThreshold = winRateThreshold;
    this.seed = seed;
  }

  public void start(long initialDelay, long period, TimeUnit unit) {
    scheduler.scheduleAtFixedRate(this::retrainAndEvaluate, initialDelay, period, unit);
    logger.info("Periodic retrainer started.");
  }

  public void stop() {
    scheduler.shutdown();
    try {
      if (!scheduler.awaitTermination(60, TimeUnit.SECONDS)) {
        scheduler.shutdownNow();
      }
    } catch (InterruptedException e) {
      scheduler.shutdownNow();
      Thread.currentThread().interrupt();
    }
    logger.info("Periodic retrainer stopped.");
  }

  public void retrainAndEvaluate() {
    logger.info("Starting retraining loop...");

    // 1. Import Games
    List<NNUETrainer.TrainingExample> dataset = GameImporter.importGames(dbPath);
    if (dataset.isEmpty()) {
      logger.warning("Dataset is empty. Skipping retraining.");
      return;
    }
    logger.info("Imported " + dataset.size() + " training examples.");

    // 2. Train New Model
    NNUETrainer trainer = new NNUETrainer(seed);
    trainer.train(dataset);
    NNUEModel candidateModel = trainer.getModel();
    logger.info("Training complete.");

    // 3. Gauntlet Match
    NNUEModel currentModel = NNUEModel.createDefault();
    SearchEngine candidateEngine = new SearchEngine(candidateModel);
    SearchEngine baselineEngine = new SearchEngine(currentModel);

    int candidateWins = 0;
    int baselineWins = 0;
    int draws = 0;

    for (int i = 0; i < gauntletGames; i++) {
      boolean candidateIsPlayer1 = (i % 2 == 0);
      int winner =
          playGame(
              candidateIsPlayer1 ? candidateEngine : baselineEngine,
              candidateIsPlayer1 ? baselineEngine : candidateEngine);

      if (winner == 1) {
        if (candidateIsPlayer1) candidateWins++;
        else baselineWins++;
      } else if (winner == 2) {
        if (candidateIsPlayer1) baselineWins++;
        else candidateWins++;
      } else {
        draws++;
      }
    }

    double candidateWinRate =
        (double) candidateWins
            / (candidateWins + baselineWins + draws == 0
                ? 1
                : candidateWins + baselineWins + draws);
    logger.info(
        String.format(
            "Gauntlet complete. Candidate wins: %d, Baseline wins: %d, Draws: %d, Win rate: %.2f%%",
            candidateWins, baselineWins, draws, candidateWinRate * 100));

    // 4. Promote if threshold met
    if (candidateWinRate >= winRateThreshold) {
      logger.info("Candidate model passed gauntlet. Promoting weights...");
      try {
        trainer.exportWeights(weightsOutputPath);
        liveSearchEngine.setNnueModel(candidateModel);
        logger.info("Weights successfully hot-swapped and exported to " + weightsOutputPath);
      } catch (IOException e) {
        logger.severe("Failed to export weights: " + e.getMessage());
      }
    } else {
      logger.info("Candidate model did not pass gauntlet. Discarding.");
    }
  }

  private int playGame(SearchEngine p1Engine, SearchEngine p2Engine) {
    Board board = new Board(12, 12);
    board.setCell(0, 0, new Cell(1, CellKind.BASE));
    board.setCell(11, 11, new Cell(2, CellKind.BASE));

    int currentPlayer = 1;
    for (int turn = 0; turn < 200; turn++) {
      SearchEngine engine = (currentPlayer == 1) ? p1Engine : p2Engine;
      List<Action> legalActions = MoveGenerator.getLegalActions(currentPlayer, board, true);

      if (legalActions.isEmpty()) {
        if (engine.isTerminal(board)) {
          return determineWinner(board);
        } else {
          return 3 - currentPlayer; // Cannot move, loses
        }
      }

      SearchResult result = SearchEngine.findBestAction(board, currentPlayer, 2, true);
      Action action = result.bestAction;
      if (action == null) {
        action = legalActions.get(0);
      }

      board = SearchEngine.applyAction(board, currentPlayer, action);

      if (engine.isTerminal(board)) {
        return determineWinner(board);
      }

      currentPlayer = 3 - currentPlayer;
    }
    return 0; // Draw by max turns
  }

  private int determineWinner(Board board) {
    boolean p1Base = false;
    boolean p2Base = false;
    for (int r = 0; r < board.rows; r++) {
      for (int c = 0; c < board.cols; c++) {
        Cell cell = board.getCell(r, c);
        if (cell != null && cell.kind == CellKind.BASE) {
          if (cell.owner == 1) p1Base = true;
          if (cell.owner == 2) p2Base = true;
        }
      }
    }
    if (p1Base && !p2Base) return 1;
    if (!p1Base && p2Base) return 2;
    return 0;
  }
}
