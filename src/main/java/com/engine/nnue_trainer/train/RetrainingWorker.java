package com.engine.nnue_trainer.train;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.nnue.NNUEModel;
import com.engine.nnue_trainer.nnue.NNUETrainer;
import com.engine.nnue_trainer.search.SearchEngine;
import com.engine.nnue_trainer.search.SearchResult;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class RetrainingWorker {

  private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
  private final String dbPath = "../virusgame/backend/data/games.db";
  private final String weightsPath = "src/main/resources/nnue_weights.json";

  @PostConstruct
  public void start() {
    // Schedule periodic retraining, e.g. every 24 hours. For now, running once every hour.
    scheduler.scheduleAtFixedRate(this::retrain, 1, 24, TimeUnit.HOURS);
  }

  public void retrain() {
    System.out.println("Starting automated retraining cycle...");
    try {
      // 1. Import games
      GameImporter importer = new GameImporter();
      List<GameImporter.TrainingPair> dataset = importer.importGames(dbPath);

      if (dataset.isEmpty()) {
        System.out.println("No games found for retraining.");
        return;
      }

      int n = dataset.size();
      float[][] X = new float[n][NNUETrainer.INPUT_SIZE];
      float[] y = new float[n];

      for (int i = 0; i < n; i++) {
        X[i] = dataset.get(i).features;
        y[i] = dataset.get(i).target;
      }

      // 2. Train new model
      NNUETrainer trainer = new NNUETrainer(System.currentTimeMillis());
      trainer.train(X, y);
      NNUEModel candidateModel = trainer.createModel();

      // 3. Eval Gate (Sparring Gauntlet)
      NNUEModel currentModel = NNUEModel.createDefault();
      if (evaluateCandidate(currentModel, candidateModel)) {
        System.out.println("Candidate model passed the gauntlet! Promoting...");
        // Save to file
        trainer.saveWeights(weightsPath);
        // Hot-swap in memory
        NNUEModel.setCachedInstance(candidateModel);
      } else {
        System.out.println("Candidate model failed the gauntlet. Discarding.");
      }

    } catch (Exception e) {
      System.err.println("Error during retraining cycle: " + e.getMessage());
      e.printStackTrace();
    }
  }

  private boolean evaluateCandidate(NNUEModel currentModel, NNUEModel candidateModel) {
    int candidateWins = 0;
    int currentWins = 0;
    int draws = 0;
    int matches = 10;

    System.out.println("Starting gauntlet: " + matches + " matches.");

    for (int i = 0; i < matches; i++) {
      boolean candidateIsPlayer1 = (i % 2 == 0);
      int winner =
          playSparringMatch(
              candidateIsPlayer1 ? candidateModel : currentModel,
              candidateIsPlayer1 ? currentModel : candidateModel);

      if (winner == 0) {
        draws++;
      } else if ((winner == 1 && candidateIsPlayer1) || (winner == 2 && !candidateIsPlayer1)) {
        candidateWins++;
      } else {
        currentWins++;
      }
    }

    System.out.println(
        String.format(
            "Gauntlet results: Candidate %d - %d Current (%d draws)",
            candidateWins, currentWins, draws));

    // Require >50% win rate (ignoring draws) to promote
    return candidateWins > currentWins;
  }

  private int playSparringMatch(NNUEModel p1Model, NNUEModel p2Model) {
    Board board = new Board(12, 12);
    board.setCell(0, 0, new Cell(1, CellKind.BASE));
    board.setCell(11, 11, new Cell(2, CellKind.BASE));

    int currentPlayer = 1;
    int turns = 0;
    int maxTurns = 200;

    // Very simplified sparring loop - 3 actions per turn
    while (turns < maxTurns) {
      NNUEModel activeModel = currentPlayer == 1 ? p1Model : p2Model;

      // We'd ideally inject this model into SearchEngine for this move.
      // SearchEngine's findBestAction is static and instantiates SearchEngine with the cached
      // default.
      // For this isolated test, we can temporarily set the global cached instance (not thread-safe
      // if other games are running),
      // OR we just use a small localized search.
      // Since SearchEngine.findBestAction is static and uses `new SearchEngine()`, we will
      // temporarily swap the global instance.
      NNUEModel previousGlobal = NNUEModel.createDefault();
      NNUEModel.setCachedInstance(activeModel);

      try {
        for (int a = 0; a < 3; a++) {
          if (isGameOver(board)) break;
          // Depth 2 for fast sparring
          SearchResult result = SearchEngine.findBestAction(board, currentPlayer, 2, false);
          if (result.bestAction != null) {
            board = SearchEngine.applyAction(board, currentPlayer, result.bestAction);
          } else {
            break; // No moves left
          }
        }
      } finally {
        NNUEModel.setCachedInstance(previousGlobal);
      }

      if (isGameOver(board)) {
        break;
      }

      currentPlayer = 3 - currentPlayer;
      turns++;
    }

    boolean p1Alive = false;
    boolean p2Alive = false;
    for (int r = 0; r < board.rows; r++) {
      for (int c = 0; c < board.cols; c++) {
        Cell cell = board.getCell(r, c);
        if (cell != null && cell.kind == CellKind.BASE) {
          if (cell.owner == 1) p1Alive = true;
          if (cell.owner == 2) p2Alive = true;
        }
      }
    }

    if (p1Alive && !p2Alive) return 1;
    if (!p1Alive && p2Alive) return 2;
    return 0; // Draw
  }

  private boolean isGameOver(Board board) {
    boolean p1Alive = false;
    boolean p2Alive = false;
    for (int r = 0; r < board.rows; r++) {
      for (int c = 0; c < board.cols; c++) {
        Cell cell = board.getCell(r, c);
        if (cell != null && cell.kind == CellKind.BASE) {
          if (cell.owner == 1) p1Alive = true;
          if (cell.owner == 2) p2Alive = true;
        }
      }
    }
    return !p1Alive || !p2Alive;
  }
}
