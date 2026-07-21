package com.engine.nnue_trainer.train;

import com.engine.nnue_trainer.board.Action;
import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.board.MoveAction;
import com.engine.nnue_trainer.board.PlaceNeutralsAction;
import com.engine.nnue_trainer.board.Pos;
import com.engine.nnue_trainer.nnue.BoardFeatureMapper;
import com.engine.nnue_trainer.search.SearchEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

public class GameImporter {

  private static final ObjectMapper mapper = new ObjectMapper();

  public static class TrainingPair {
    public float[] features;
    public float target;
  }

  public List<TrainingPair> importGames(String dbPath) {
    List<TrainingPair> dataset = new ArrayList<>();
    String url = "jdbc:sqlite:" + dbPath;

    String query =
        "SELECT id, result, pgn_content "
            + "FROM games "
            + "WHERE rows = 12 AND cols = 12 AND pgn_content IS NOT NULL AND pgn_content != 'null' "
            + "AND player1_name LIKE 'GoBot%' AND player2_name LIKE 'GoBot%'";

    try (Connection conn = DriverManager.getConnection(url);
        PreparedStatement pstmt = conn.prepareStatement(query);
        ResultSet rs = pstmt.executeQuery()) {

      int validGames = 0;
      while (rs.next()) {
        validGames++;
        long gameId = rs.getLong("id");
        int result = rs.getInt("result");
        String pgnContent = rs.getString("pgn_content");

        processGame(pgnContent, result, dataset);
      }
      System.out.println("Found " + validGames + " valid 12x12 games.");
    } catch (Exception e) {
      System.err.println("Error reading from database: " + e.getMessage());
    }

    System.out.println(
        "Successfully generated dataset with " + dataset.size() + " position records.");
    return dataset;
  }

  private void processGame(String pgnContent, int result, List<TrainingPair> dataset) {
    try {
      JsonNode turns = mapper.readTree(pgnContent);
      Board board = new Board(12, 12);
      board.setCell(0, 0, new Cell(1, CellKind.BASE));
      board.setCell(11, 11, new Cell(2, CellKind.BASE));

      for (JsonNode turnNode : turns) {
        int player = turnNode.get("player").asInt();
        JsonNode moves = turnNode.get("moves");

        if (moves != null && moves.isArray()) {
          for (JsonNode moveNode : moves) {
            String mType = moveNode.has("type") ? moveNode.get("type").asText() : "";
            Action action = null;

            if ("place".equals(mType) || "attack".equals(mType)) {
              int r =
                  moveNode.has("row")
                      ? moveNode.get("row").asInt()
                      : (moveNode.has("Row") ? moveNode.get("Row").asInt() : -1);
              int c =
                  moveNode.has("col")
                      ? moveNode.get("col").asInt()
                      : (moveNode.has("Col") ? moveNode.get("Col").asInt() : -1);
              if (r >= 0 && c >= 0) {
                action = new MoveAction(new Pos(r, c));
              }
            } else if ("neutral".equals(mType)) {
              JsonNode cells = moveNode.get("cells");
              if (cells != null && cells.isArray() && cells.size() == 2) {
                JsonNode c1 = cells.get(0);
                JsonNode c2 = cells.get(1);
                int r1 =
                    c1.has("row")
                        ? c1.get("row").asInt()
                        : (c1.has("Row") ? c1.get("Row").asInt() : -1);
                int col1 =
                    c1.has("col")
                        ? c1.get("col").asInt()
                        : (c1.has("Col") ? c1.get("Col").asInt() : -1);
                int r2 =
                    c2.has("row")
                        ? c2.get("row").asInt()
                        : (c2.has("Row") ? c2.get("Row").asInt() : -1);
                int col2 =
                    c2.has("col")
                        ? c2.get("col").asInt()
                        : (c2.has("Col") ? c2.get("Col").asInt() : -1);
                if (r1 >= 0 && col1 >= 0 && r2 >= 0 && col2 >= 0) {
                  action = new PlaceNeutralsAction(new Pos(r1, col1), new Pos(r2, col2));
                }
              }
            }

            if (action != null) {
              board = SearchEngine.applyAction(board, player, action);
            }
          }
        }

        float[] features = BoardFeatureMapper.map(board, player);
        float target = (result == player) ? 1.0f : -1.0f;

        TrainingPair pair = new TrainingPair();
        pair.features = features;
        pair.target = target;
        dataset.add(pair);
      }
    } catch (Exception e) {
      System.err.println("Error parsing PGN: " + e.getMessage());
    }
  }
}
