package com.engine.nnue_trainer.train;

import com.engine.nnue_trainer.board.Action;
import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.board.MoveAction;
import com.engine.nnue_trainer.board.PlaceNeutralsAction;
import com.engine.nnue_trainer.board.Pos;
import com.engine.nnue_trainer.nnue.BoardFeatureMapper;
import com.engine.nnue_trainer.nnue.NNUETrainer;
import com.engine.nnue_trainer.search.SearchEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class GameImporter {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  public ImportResult importGames(Path dbPath) throws SQLException, IOException {
    return importGames(new ImportOptions(dbPath, null, true));
  }

  public ImportResult importGames(ImportOptions options) throws SQLException, IOException {
    if (!Files.exists(options.dbPath())) {
      throw new IOException("Database not found: " + options.dbPath());
    }

    List<NNUETrainer.TrainingExample> examples = new ArrayList<>();
    Set<String> seenPgn = new HashSet<>();
    int importedGames = 0;
    int skippedDuplicates = 0;

    StringBuilder sql =
        new StringBuilder(
            "SELECT id, result, pgn_content FROM games "
                + "WHERE rows = 12 AND cols = 12 "
                + "AND pgn_content IS NOT NULL AND pgn_content != 'null' "
                + "AND player1_name LIKE 'GoBot%' AND player2_name LIKE 'GoBot%'");
    if (options.minStartedAt() != null && !options.minStartedAt().isBlank()) {
      sql.append(" AND started_at > ?");
    }

    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + options.dbPath());
        PreparedStatement statement = connection.prepareStatement(sql.toString())) {
      if (options.minStartedAt() != null && !options.minStartedAt().isBlank()) {
        statement.setString(1, options.minStartedAt());
      }

      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          long gameId = rows.getLong("id");
          int result = rows.getInt("result");
          String pgnContent = rows.getString("pgn_content");
          if (options.deduplicatePgn() && !seenPgn.add(pgnContent)) {
            skippedDuplicates++;
            continue;
          }
          examples.addAll(
              replayGame(
                  gameId,
                  pgnContent,
                  result,
                  options.labelMode(),
                  options.lambdaVal(),
                  options.gammaVal()));
          importedGames++;
        }
      }
    }

    return new ImportResult(examples, importedGames, skippedDuplicates);
  }

  public List<NNUETrainer.TrainingExample> replayGame(String pgnContent, int result)
      throws IOException {
    return replayGame(-1L, pgnContent, result, LabelMode.OUTCOME, 0.5f, 0.98f);
  }

  public List<NNUETrainer.TrainingExample> replayGame(
      String pgnContent, int result, LabelMode labelMode, float lambdaVal, float gammaVal)
      throws IOException {
    return replayGame(-1L, pgnContent, result, labelMode, lambdaVal, gammaVal);
  }

  private List<NNUETrainer.TrainingExample> replayGame(
      long gameId,
      String pgnContent,
      int result,
      LabelMode labelMode,
      float lambdaVal,
      float gammaVal)
      throws IOException {
    JsonNode turns;
    try {
      turns = MAPPER.readTree(pgnContent);
    } catch (IOException e) {
      throw new IOException("Invalid PGN JSON for game " + gameId, e);
    }
    if (!turns.isArray()) {
      throw new IOException("PGN must be an array for game " + gameId);
    }

    List<NNUETrainer.TrainingExample> examples = new ArrayList<>();
    Board board = initialBoard();

    int totalTurns = turns.size();
    for (int turnIdx = 0; turnIdx < totalTurns; turnIdx++) {
      JsonNode turn = turns.get(turnIdx);
      int player = requiredInt(turn, "player", "Player");
      JsonNode moves = turn.get("moves");
      if (moves == null) {
        moves = turn.get("Moves");
      }
      if (moves != null) {
        if (!moves.isArray()) {
          throw new IOException("moves must be an array for game " + gameId);
        }
        for (JsonNode move : moves) {
          Action action = parseAction(move);
          board = SearchEngine.applyAction(board, player, action);
        }
      }

      float outcome = target(result, player);
      float targetVal = outcome;

      if (labelMode == LabelMode.DISCOUNTED) {
        targetVal = outcome * (float) Math.pow(gammaVal, totalTurns - 1 - turnIdx);
      } else if (labelMode == LabelMode.TD_LEAF) {
        float lambdaEff = lambdaVal;
        if (totalTurns > 1) {
          lambdaEff = lambdaVal + (1.0f - lambdaVal) * ((float) turnIdx / (totalTurns - 1));
        }
        float searchEval = 0.0f;
        if (turn.has("eval")) {
          searchEval = (float) turn.get("eval").asDouble();
        } else if (turn.has("score")) {
          searchEval = (float) turn.get("score").asDouble();
        }
        targetVal = (1.0f - lambdaEff) * searchEval + lambdaEff * outcome;
      }

      examples.add(
          new NNUETrainer.TrainingExample(BoardFeatureMapper.map(board, player), targetVal));
    }

    return examples;
  }

  private static Board initialBoard() {
    Board board = new Board(12, 12);
    board.setCell(0, 0, new Cell(1, CellKind.BASE));
    board.setCell(11, 11, new Cell(2, CellKind.BASE));
    return board;
  }

  private static Action parseAction(JsonNode move) throws IOException {
    String type = requiredText(move, "type", "Type").toLowerCase(Locale.ROOT);
    if ("place".equals(type) || "attack".equals(type) || "move".equals(type)) {
      return new MoveAction(
          new Pos(requiredInt(move, "row", "Row"), requiredInt(move, "col", "Col")));
    }
    if ("neutral".equals(type) || "neutrals".equals(type)) {
      JsonNode cells = move.get("cells");
      if (cells == null) {
        cells = move.get("Cells");
      }
      if (cells == null || !cells.isArray() || cells.size() != 2) {
        throw new IOException("neutral action must contain exactly two cells");
      }
      return new PlaceNeutralsAction(
          new Pos(requiredInt(cells.get(0), "row", "Row"), requiredInt(cells.get(0), "col", "Col")),
          new Pos(
              requiredInt(cells.get(1), "row", "Row"), requiredInt(cells.get(1), "col", "Col")));
    }
    throw new IOException("Unsupported move type: " + type);
  }

  private static String requiredText(JsonNode node, String primary, String fallback)
      throws IOException {
    JsonNode value = node.has(primary) ? node.get(primary) : node.get(fallback);
    if (value == null) {
      throw new IOException("Missing required field: " + primary);
    }
    return value.asText();
  }

  private static int requiredInt(JsonNode node, String primary, String fallback)
      throws IOException {
    JsonNode value = node.has(primary) ? node.get(primary) : node.get(fallback);
    if (value == null) {
      throw new IOException("Missing required field: " + primary);
    }
    return value.asInt();
  }

  private static float target(int result, int player) {
    if (result == 0) {
      return 0.0f;
    }
    return result == player ? 1.0f : -1.0f;
  }

  public record ImportOptions(
      Path dbPath,
      String minStartedAt,
      boolean deduplicatePgn,
      LabelMode labelMode,
      float lambdaVal,
      float gammaVal) {
    public ImportOptions(Path dbPath, String minStartedAt, boolean deduplicatePgn) {
      this(dbPath, minStartedAt, deduplicatePgn, LabelMode.OUTCOME, 0.5f, 0.98f);
    }
  }

  public enum LabelMode {
    OUTCOME,
    TD_LEAF,
    DISCOUNTED
  }

  public record ImportResult(
      List<NNUETrainer.TrainingExample> examples, int importedGames, int skippedDuplicates) {}
}
