package com.engine.nnue_trainer.train;

import com.engine.nnue_trainer.board.Action;
import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.board.MoveGenerator;
import com.engine.nnue_trainer.nnue.BoardFeatureMapper;
import com.engine.nnue_trainer.search.SearchEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class SelfPlayGenerator {

  public static class Config {
    public int numGames = 50;
    public int maxTurns = 100;
    public double epsilon = 0.1;
    public int exploreTurns = 6;
    public int searchDepth = 2;
    public long timeLimitMs = 0;
    public long seed = 0;
  }

  public static class TrainingRecord {
    public float[] features;
    public float target;

    public TrainingRecord(float[] features, float target) {
      this.features = features;
      this.target = target;
    }
  }

  public static class GenerationResult {
    public List<TrainingRecord> dataset;
    public double distinctGameRatio;

    public GenerationResult(List<TrainingRecord> dataset, double distinctGameRatio) {
      this.dataset = dataset;
      this.distinctGameRatio = distinctGameRatio;
    }
  }

  private static class TurnData {
    public Board board;
    public int activePlayer;

    public TurnData(Board board, int activePlayer) {
      this.board = board;
      this.activePlayer = activePlayer;
    }
  }

  public static void main(String[] args) {
    Config config = new Config();
    String outputPath = "src/main/resources/self_play_data.json";

    if (args.length > 0) {
      try {
        config.numGames = Integer.parseInt(args[0]);
      } catch (NumberFormatException e) {
        System.err.println("Invalid number of games. Using default: " + config.numGames);
      }
    }
    if (args.length > 1) {
      outputPath = args[1];
    }

    System.out.println("Starting self-play generation for " + config.numGames + " games...");
    GenerationResult result = generate(config, null);
    System.out.println("Generation complete. Total records: " + result.dataset.size());
    System.out.println("Distinct game ratio: " + result.distinctGameRatio);
    saveDataset(result.dataset, outputPath);
  }

  public static GenerationResult generate(Config config, SearchEngine customEngine) {
    List<TrainingRecord> dataset = new ArrayList<>();
    Random random = config.seed != 0 ? new Random(config.seed) : new Random();
    SearchEngine engine = customEngine != null ? customEngine : new SearchEngine();

    Set<Integer> uniquePositionHashes = new HashSet<>();
    int totalPositions = 0;

    for (int game = 1; game <= config.numGames; game++) {
      // System.out.println("Simulating game " + game + "/" + config.numGames);
      List<TurnData> turns = new ArrayList<>();
      Board board = new Board(12, 12);

      // Initialize bases
      board.setCell(0, 0, new Cell(1, CellKind.BASE));
      board.setCell(11, 11, new Cell(2, CellKind.BASE));

      int currentPlayer = 1;
      int winner = 0;

      for (int turn = 0; turn < config.maxTurns; turn++) {
        boolean canPlaceNeutral = true;
        for (int actionIdx = 0; actionIdx < 3; actionIdx++) {
          // Collect board snapshot BEFORE move
          turns.add(new TurnData(copyBoard(board), currentPlayer));

          List<Action> legalActions =
              MoveGenerator.getLegalActions(currentPlayer, board, canPlaceNeutral);
          if (legalActions.isEmpty()) {
            if (engine.isTerminal(board)) {
              winner = determineWinner(board);
            } else {
              winner = 3 - currentPlayer;
            }
            break;
          }

          Action chosenAction = null;
          if (turn <= config.exploreTurns && random.nextDouble() < config.epsilon) {
            // Exploration
            chosenAction = legalActions.get(random.nextInt(legalActions.size()));
          } else {
            // Exploitation
            if (engine.getNnueModel() != null) {
              chosenAction =
                  engine.findBestActionUsingModel(
                          board, currentPlayer, config.searchDepth, canPlaceNeutral)
                      .bestAction;
            } else {
              chosenAction =
                  SearchEngine.findBestAction(
                          board, currentPlayer, config.searchDepth, canPlaceNeutral)
                      .bestAction;
            }
            if (chosenAction == null) {
              chosenAction = legalActions.get(0); // fallback
            }
          }

          if (chosenAction instanceof com.engine.nnue_trainer.board.PlaceNeutralsAction) {
            canPlaceNeutral = false;
            board = SearchEngine.applyAction(board, currentPlayer, chosenAction);
            break; // turn ends immediately on placement
          } else {
            board = SearchEngine.applyAction(board, currentPlayer, chosenAction);
          }

          if (engine.isTerminal(board)) {
            winner = determineWinner(board);
            break;
          }
        }
        if (winner != 0) break;
        currentPlayer = 3 - currentPlayer;
      }

      // Process collected turns to dataset
      for (TurnData turnData : turns) {
        float target = 0.0f;
        if (winner != 0) {
          target = (winner == turnData.activePlayer) ? 1.0f : -1.0f;
        }
        float[] features = BoardFeatureMapper.map(turnData.board, turnData.activePlayer);
        dataset.add(new TrainingRecord(features, target));

        uniquePositionHashes.add(Arrays.hashCode(features));
        totalPositions++;
      }
    }

    double distinctGameRatio =
        totalPositions > 0 ? (double) uniquePositionHashes.size() / totalPositions : 0.0;
    return new GenerationResult(dataset, distinctGameRatio);
  }

  private static int determineWinner(Board board) {
    boolean player1BaseAlive = false;
    boolean player2BaseAlive = false;

    for (int r = 0; r < board.rows; r++) {
      for (int c = 0; c < board.cols; c++) {
        Cell cell = board.getCell(r, c);
        if (cell != null && cell.kind == CellKind.BASE) {
          if (cell.owner == 1) player1BaseAlive = true;
          if (cell.owner == 2) player2BaseAlive = true;
        }
      }
    }

    if (player1BaseAlive && !player2BaseAlive) return 1;
    if (!player1BaseAlive && player2BaseAlive) return 2;
    return 0; // Draw or both destroyed (shouldn't happen in standard play but just in case)
  }

  private static Board copyBoard(Board original) {
    Board copy = new Board(original.rows, original.cols);
    for (int r = 0; r < original.rows; r++) {
      for (int c = 0; c < original.cols; c++) {
        Cell cell = original.getCell(r, c);
        if (cell != null) {
          copy.setCell(r, c, new Cell(cell.owner, cell.kind));
        }
      }
    }
    return copy;
  }

  private static void saveDataset(List<TrainingRecord> dataset, String filepath) {
    try {
      File file = new File(filepath);
      File parent = file.getParentFile();
      if (parent != null) {
        parent.mkdirs();
      }
      ObjectMapper mapper = new ObjectMapper();
      mapper.writerWithDefaultPrettyPrinter().writeValue(file, dataset);
      System.out.println("Dataset saved to " + filepath);
    } catch (IOException e) {
      System.err.println("Failed to save dataset to " + filepath);
      e.printStackTrace();
    }
  }
}
