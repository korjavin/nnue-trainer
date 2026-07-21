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
import java.util.List;
import java.util.Random;

public class SelfPlayGenerator {

  private static final int DEFAULT_GAMES = 50;
  private static final int MAX_TURNS = 100;
  private static final double EPSILON = 0.1;
  private static final int SEARCH_DEPTH = 2; // configurable, standard minimax depth

  public static class TrainingRecord {
    public float[] features;
    public float target;

    public TrainingRecord(float[] features, float target) {
      this.features = features;
      this.target = target;
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
    int numGames = DEFAULT_GAMES;
    String outputPath = "src/main/resources/self_play_data.json";

    if (args.length > 0) {
      try {
        numGames = Integer.parseInt(args[0]);
      } catch (NumberFormatException e) {
        System.err.println("Invalid number of games. Using default: " + DEFAULT_GAMES);
      }
    }
    if (args.length > 1) {
      outputPath = args[1];
    }

    System.out.println("Starting self-play generation for " + numGames + " games...");
    List<TrainingRecord> dataset = new ArrayList<>();
    Random random = new Random();
    SearchEngine engine = new SearchEngine();

    for (int game = 1; game <= numGames; game++) {
      System.out.println("Simulating game " + game + "/" + numGames);
      List<TurnData> turns = new ArrayList<>();
      Board board = new Board(12, 12);

      // Initialize bases
      board.setCell(0, 0, new Cell(1, CellKind.BASE));
      board.setCell(11, 11, new Cell(2, CellKind.BASE));

      int currentPlayer = 1;
      int winner = 0;

      for (int turn = 0; turn < MAX_TURNS; turn++) {
        // Collect board snapshot BEFORE move
        turns.add(new TurnData(copyBoard(board), currentPlayer));

        List<Action> legalActions = MoveGenerator.getLegalActions(currentPlayer, board, true);
        if (legalActions.isEmpty()) {
          // Player has no moves, check if terminal
          if (engine.isTerminal(board)) {
            winner = determineWinner(board);
          } else {
            winner = 3 - currentPlayer; // Can't move -> loses? Treat as opponent wins if no moves
          }
          break;
        }

        Action chosenAction = null;
        if (random.nextDouble() < EPSILON) {
          // Exploration
          chosenAction = legalActions.get(random.nextInt(legalActions.size()));
        } else {
          // Exploitation
          chosenAction = SearchEngine.findBestAction(board, currentPlayer, SEARCH_DEPTH, true);
          if (chosenAction == null) {
            chosenAction = legalActions.get(0); // fallback
          }
        }

        // Apply action
        board = SearchEngine.applyAction(board, currentPlayer, chosenAction);

        if (engine.isTerminal(board)) {
          winner = determineWinner(board);
          break;
        }

        currentPlayer = 3 - currentPlayer;
      }

      // If loop ended and winner is still 0, it's a draw (reached MAX_TURNS or no terminal
      // condition met)

      // Process collected turns to dataset
      for (TurnData turnData : turns) {
        float target = 0.0f;
        if (winner != 0) {
          target = (winner == turnData.activePlayer) ? 1.0f : -1.0f;
        }
        float[] features = BoardFeatureMapper.map(turnData.board, turnData.activePlayer);
        dataset.add(new TrainingRecord(features, target));
      }
    }

    System.out.println("Generation complete. Total records: " + dataset.size());
    saveDataset(dataset, outputPath);
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
