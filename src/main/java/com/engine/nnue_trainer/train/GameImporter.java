package com.engine.nnue_trainer.train;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.board.MoveAction;
import com.engine.nnue_trainer.board.PlaceNeutralsAction;
import com.engine.nnue_trainer.board.Pos;
import com.engine.nnue_trainer.nnue.BoardFeatureMapper;
import com.engine.nnue_trainer.nnue.NNUETrainer;
import com.engine.nnue_trainer.search.SearchEngine;
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
                  board = SearchEngine.applyAction(board, player, new MoveAction(new Pos(r, c)));
                }
              } else if ("neutrals".equals(type) || "neutral".equals(type)) {
                List<Map<String, Object>> cells = (List<Map<String, Object>>) move.get("cells");
                if (cells != null && cells.size() == 2) {
                  Integer r1 = (Integer) cells.get(0).get("row");
                  if (r1 == null) r1 = (Integer) cells.get(0).get("Row");
                  Integer c1 = (Integer) cells.get(0).get("col");
                  if (c1 == null) c1 = (Integer) cells.get(0).get("Col");

                  Integer r2 = (Integer) cells.get(1).get("row");
                  if (r2 == null) r2 = (Integer) cells.get(1).get("Row");
                  Integer c2 = (Integer) cells.get(1).get("col");
                  if (c2 == null) c2 = (Integer) cells.get(1).get("Col");

                  if (r1 != null && c1 != null && r2 != null && c2 != null) {
                    board =
                        SearchEngine.applyAction(
                            board,
                            player,
                            new PlaceNeutralsAction(new Pos(r1, c1), new Pos(r2, c2)));
                  }
                }
              }
            }
          }

          float[] features = BoardFeatureMapper.map(board, player);

          float target = 0.0f;
          if (result == 1 || result == 2) {
            target = (result == player) ? 1.0f : -1.0f;
          }

          dataset.add(new NNUETrainer.TrainingExample(features, target));
        }
      }

    } catch (Exception e) {
      System.err.println("Error importing games: " + e.getMessage());
    }

    return dataset;
  }
}
