package com.engine.nnue_trainer.train;

import com.engine.nnue_trainer.board.Action;
import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.board.MoveAction;
import com.engine.nnue_trainer.board.PlaceNeutralsAction;
import com.engine.nnue_trainer.board.Pos;
import com.engine.nnue_trainer.search.SearchEngine;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shared games.db replay: turns a stored {@code pgn_content} turn list into the sequence of
 * positions the pattern miners consume. Extracted from {@link GamesDbPatternMiner} (bd
 * nnue-trainer-d4a.5.1) so the 5x5 miner and the v3 absolute-feature miner share ONE definition of
 * "position".
 *
 * <p>A "position" is the board <b>before</b> a turn; STM = that turn's player. Consumers evaluate it
 * with {@link #MOVES_LEFT} = 3 (fresh turn start — a fixed assumption; the real per-ply movesLeft is
 * not reconstructed from the PGN). Moves are applied through {@link SearchEngine#applyAction} so
 * virus-conversion/connectivity rules apply.
 *
 * <p>A game yields a skip reason instead of snapshots when a turn has no {@code player} ({@code
 * no_player}), a player outside 1..2 ({@code multiplayer} — applyAction only resolves connectivity
 * for players 1-2), or replay throws ({@code replay_error}).
 */
public final class GamesDbReplay {

  /** Fresh-turn assumption shared by every games.db miner. */
  public static final int MOVES_LEFT = 3;

  private GamesDbReplay() {}

  /** Board before a turn, with the side to move and the neutral-budget state at that point. */
  public static final class Snapshot {
    public final Board board;
    public final int stm;
    public final boolean[] neutralUsed;

    public Snapshot(Board board, int stm, boolean[] neutralUsed) {
      this.board = board;
      this.stm = stm;
      this.neutralUsed = neutralUsed;
    }
  }

  /** Replay outcome: snapshots, or a skip reason (then {@code snapshots} is null). */
  public static final class Replay {
    public final List<Snapshot> snapshots;
    public final String skipReason; // null == ok

    public Replay(List<Snapshot> snapshots, String skipReason) {
      this.snapshots = snapshots;
      this.skipReason = skipReason;
    }
  }

  /** Replay a single game; returns snapshots (before each turn) or a skip reason. */
  public static Replay replay(int rows, int cols, JsonNode turns) {
    try {
      Board board = initialBoard(rows, cols);
      boolean[] neutralUsed = new boolean[2];
      List<Snapshot> snaps = new ArrayList<>();

      for (JsonNode turn : turns) {
        JsonNode playerNode = turn.get("player");
        if (playerNode == null) {
          return new Replay(null, "no_player");
        }
        int player = playerNode.asInt();
        if (player < 1 || player > 2) {
          return new Replay(null, "multiplayer");
        }

        snaps.add(new Snapshot(board, player, neutralUsed.clone()));

        JsonNode moves = turn.get("moves");
        if (moves != null && moves.isArray()) {
          for (JsonNode mv : moves) {
            Action action = parseAction(mv);
            if (action instanceof PlaceNeutralsAction) {
              neutralUsed[player - 1] = true;
            }
            board = SearchEngine.applyAction(board, player, action);
          }
        }
      }
      return new Replay(snaps, null);
    } catch (Exception e) {
      return new Replay(null, "replay_error");
    }
  }

  /** Empty board with the two bases in opposite corners. */
  public static Board initialBoard(int rows, int cols) {
    Board board = new Board(rows, cols);
    board.setCell(0, 0, new Cell(1, CellKind.BASE));
    board.setCell(rows - 1, cols - 1, new Cell(2, CellKind.BASE));
    return board;
  }

  /** Parse one stored move node into an engine action. */
  public static Action parseAction(JsonNode move) {
    String type = move.get("type").asText().toLowerCase(Locale.ROOT);
    if ("place".equals(type) || "attack".equals(type) || "move".equals(type)) {
      return new MoveAction(new Pos(move.get("row").asInt(), move.get("col").asInt()));
    }
    if ("neutral".equals(type) || "neutrals".equals(type)) {
      JsonNode cells = move.get("cells");
      if (cells == null || !cells.isArray() || cells.size() != 2) {
        throw new IllegalArgumentException("neutral action must contain exactly two cells");
      }
      return new PlaceNeutralsAction(
          new Pos(cells.get(0).get("row").asInt(), cells.get(0).get("col").asInt()),
          new Pos(cells.get(1).get("row").asInt(), cells.get(1).get("col").asInt()));
    }
    throw new IllegalArgumentException("Unsupported move type: " + type);
  }
}
