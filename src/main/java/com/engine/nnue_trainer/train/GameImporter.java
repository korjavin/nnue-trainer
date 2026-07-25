package com.engine.nnue_trainer.train;

import com.engine.nnue_trainer.nnue.BoardFeatureMapper;
import com.engine.nnue_trainer.nnue.NNUETrainer;
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
          int result = rows.getInt("result");
          String pgnContent = rows.getString("pgn_content");
          if (options.deduplicatePgn() && !seenPgn.add(pgnContent)) {
            skippedDuplicates++;
            continue;
          }
          List<NNUETrainer.TrainingExample> replayed = replayPgn(pgnContent, result);
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

  /** Replays a game into training examples; empty when the game cannot be replayed. */
  public List<NNUETrainer.TrainingExample> replayGame(String pgnContent, int result) {
    List<NNUETrainer.TrainingExample> examples = replayPgn(pgnContent, result);
    return examples == null ? List.of() : examples;
  }

  /**
   * Turn-by-turn examples, or {@code null} when the game is unusable — the rules reject a recorded
   * turn, or the PGN is malformed. Both are per-game skips: a single bad row must not abort a
   * retrain cycle and discard every example already accumulated.
   *
   * <p>Delegates to {@link GamesDbReplay}, the same legality-checked replay the miners use, so a
   * turn the rules reject (a fourth action, a mid-turn or repeated neutral pair, a
   * disconnected/ineligible target) skips the game instead of training on a board the game never
   * contained, and captures/base-connectivity follow the real transition rather than {@code
   * SearchEngine.applyAction}.
   *
   * <p>An example is the board <b>before</b> a turn, oriented to that turn's player — {@link
   * GamesDbReplay}'s definition of a position, and the same convention {@code SelfPlayGenerator}
   * records and the engine queries at inference. Labelling the board <b>after</b> the turn with the
   * mover's perspective (what this did) orients the identical board the opposite way from every
   * other producer, so the two corpora {@code PeriodicRetrainer} concatenates disagreed on which
   * side of a position "self" means.
   */
  private List<NNUETrainer.TrainingExample> replayPgn(String pgnContent, int result) {
    if (pgnContent == null || pgnContent.isBlank()) {
      return null;
    }
    JsonNode turns;
    try {
      turns = MAPPER.readTree(pgnContent);
    } catch (IOException e) {
      return null;
    }
    if (turns == null || !turns.isArray()) {
      return null;
    }

    GamesDbReplay.Replay replay = GamesDbReplay.replay(12, 12, turns);
    if (replay.skipReason != null) {
      return null;
    }

    List<NNUETrainer.TrainingExample> examples = new ArrayList<>(replay.snapshots.size());
    for (GamesDbReplay.Snapshot s : replay.snapshots) {
      examples.add(
          new NNUETrainer.TrainingExample(
              BoardFeatureMapper.map(s.board, s.stm), target(result, s.stm)));
    }
    return examples;
  }

  private static float target(int result, int player) {
    if (result == 0) {
      return 0.0f;
    }
    return result == player ? 1.0f : -1.0f;
  }

  public record ImportOptions(Path dbPath, String minStartedAt, boolean deduplicatePgn) {}

  /**
   * {@code skippedIllegalGames}: games dropped because they cannot be replayed — the rules reject a
   * recorded turn, or the PGN is malformed.
   */
  public record ImportResult(
      List<NNUETrainer.TrainingExample> examples,
      int importedGames,
      int skippedDuplicates,
      int skippedIllegalGames) {}
}
