package com.engine.nnue_trainer.train;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.nnue.BoardFeatureMapper;
import com.engine.nnue_trainer.nnue.NNUETrainer;
import com.engine.nnue_trainer.search.gobot.GoState;
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
    int skippedIllegal = 0;

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
          List<NNUETrainer.TrainingExample> replayed = replayGame(gameId, pgnContent, result);
          if (replayed == null) {
            skippedIllegal++;
            continue;
          }
          examples.addAll(replayed);
          importedGames++;
        }
      }
    }

    return new ImportResult(examples, importedGames, skippedDuplicates, skippedIllegal);
  }

  /** Replays a game into training examples; empty when the rules reject one of its turns. */
  public List<NNUETrainer.TrainingExample> replayGame(String pgnContent, int result)
      throws IOException {
    List<NNUETrainer.TrainingExample> examples = replayGame(-1L, pgnContent, result);
    return examples == null ? List.of() : examples;
  }

  /** Returns the turn-by-turn examples, or {@code null} if the rules reject a recorded turn. */
  private List<NNUETrainer.TrainingExample> replayGame(long gameId, String pgnContent, int result)
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
    Board board = GamesDbReplay.initialBoard(12, 12);
    boolean[] neutralUsed = new boolean[2];

    for (JsonNode turn : turns) {
      int player = requiredInt(turn, "player", "Player");
      JsonNode moves = turn.get("moves");
      if (moves == null) {
        moves = turn.get("Moves");
      }
      if (moves != null) {
        if (!moves.isArray()) {
          throw new IOException("moves must be an array for game " + gameId);
        }
        // Same legality-checked replay the miners use, so a recorded turn the rules reject (a
        // fourth action, a mid-turn or repeated neutral pair, a disconnected/ineligible target)
        // skips the game instead of training on a board the game never contained. It also carries
        // the board-transition fidelity SearchEngine.applyAction lacks: captures are FORTIFIED and
        // cells that lose base-connectivity are kept.
        GoState state;
        try {
          state = GamesDbReplay.applyTurn(board, player, moves, neutralUsed);
        } catch (RuntimeException e) {
          throw new IOException("Invalid move for game " + gameId, e);
        }
        if (state == null) {
          return null;
        }
        board = state.toBoard();
        for (int p = 1; p <= 2; p++) {
          neutralUsed[p - 1] = state.neutralUsed(p);
        }
      }

      examples.add(
          new NNUETrainer.TrainingExample(
              BoardFeatureMapper.map(board, player), target(result, player)));
    }

    return examples;
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

  public record ImportOptions(Path dbPath, String minStartedAt, boolean deduplicatePgn) {}

  /** {@code skippedIllegalGames}: games dropped because the rules reject a recorded turn. */
  public record ImportResult(
      List<NNUETrainer.TrainingExample> examples,
      int importedGames,
      int skippedDuplicates,
      int skippedIllegalGames) {}
}
