package com.engine.nnue_trainer.train;

import com.engine.nnue_trainer.board.BaseConnectionSearch;
import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.nnue.BoardFeatureMapper;
import com.engine.nnue_trainer.nnue.NNUETrainer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class GameImporter {

  private static final String DEFAULT_DB_PATH = "../virusgame/backend/data/games.db";

  public static List<NNUETrainer.TrainingExample> importGames(String dbPath) {
    if (dbPath == null || dbPath.isEmpty()) {
      dbPath = DEFAULT_DB_PATH;
    }

    File dbFile = new File(dbPath);
    if (!dbFile.exists()) {
      System.err.println("Error: Database not found at " + dbPath);
      return new ArrayList<>();
    }

    String url = "jdbc:sqlite:" + dbPath;
    List<NNUETrainer.TrainingExample> dataset = new ArrayList<>();
    ObjectMapper mapper = new ObjectMapper();

    try (Connection conn = DriverManager.getConnection(url);
        Statement stmt = conn.createStatement();
        ResultSet rs =
            stmt.executeQuery(
                "SELECT id, result, pgn_content FROM games WHERE rows = 12 AND cols = 12 "
                    + "AND pgn_content IS NOT NULL AND pgn_content != 'null' "
                    + "AND player1_name LIKE 'GoBot%' AND player2_name LIKE 'GoBot%'")) {

      while (rs.next()) {
        int gameId = rs.getInt("id");
        int result = rs.getInt("result");
        String pgnContent = rs.getString("pgn_content");

        Board board = new Board(12, 12);
        board.setCell(0, 0, new Cell(1, CellKind.BASE));
        board.setCell(11, 11, new Cell(2, CellKind.BASE));

        List<Map<String, Object>> turns;
        try {
          turns = mapper.readValue(pgnContent, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
          System.err.println("Error decoding PGN for game " + gameId + ": " + e.getMessage());
          continue;
        }

        for (Map<String, Object> turnData : turns) {
          int player = (Integer) turnData.get("player");
          List<Map<String, Object>> moves = (List<Map<String, Object>>) turnData.get("moves");

          if (moves != null) {
            for (Map<String, Object> move : moves) {
              String type = (String) move.get("type");
              if ("place".equals(type) || "attack".equals(type)) {
                Integer r = (Integer) move.get("row");
                if (r == null) r = (Integer) move.get("Row");
                Integer c = (Integer) move.get("col");
                if (c == null) c = (Integer) move.get("Col");

                if (r != null && c != null) {
                  board.setCell(r, c, new Cell(player, CellKind.NORMAL));
                }
              } else if ("neutral".equals(type)) {
                List<Map<String, Object>> cells = (List<Map<String, Object>>) move.get("cells");
                if (cells != null) {
                  for (Map<String, Object> cellInfo : cells) {
                    Integer r = (Integer) cellInfo.get("row");
                    if (r == null) r = (Integer) cellInfo.get("Row");
                    Integer c = (Integer) cellInfo.get("col");
                    if (c == null) c = (Integer) cellInfo.get("Col");
                    if (r != null && c != null) {
                      board.setCell(r, c, new Cell(0, CellKind.NEUTRAL));
                    }
                  }
                }
              }
            }
          }

          // Cleanup disconnected pieces (same logic as SearchEngine.applyAction /
          // BaseConnectionSearch)
          for (int p = 1; p <= 2; p++) {
            boolean[] connected = BaseConnectionSearch.connected(p, board);
            for (int r = 0; r < board.rows; r++) {
              for (int c = 0; c < board.cols; c++) {
                Cell cell = board.getCell(r, c);
                if (cell != null
                    && cell.owner == p
                    && cell.kind != CellKind.EMPTY
                    && cell.kind != CellKind.NEUTRAL
                    && cell.kind != CellKind.BASE) { // Base is never cleaned up
                  if (!connected[r * board.cols + c]) {
                    board.setCell(r, c, new Cell(0, CellKind.EMPTY));
                  }
                }
              }
            }
          }

          float[] features = BoardFeatureMapper.map(board, player);
          float target = (result == player) ? 1.0f : -1.0f;

          dataset.add(new NNUETrainer.TrainingExample(features, target));
        }
      }

    } catch (Exception e) {
      System.err.println("Error importing games: " + e.getMessage());
    }

    return dataset;
  }
}
