package com.engine.nnue_trainer.train;

import com.engine.nnue_trainer.board.Action;
import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.board.MoveAction;
import com.engine.nnue_trainer.board.PlaceNeutralsAction;
import com.engine.nnue_trainer.board.Pos;
import com.engine.nnue_trainer.search.gobot.GoState;
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
 * <p>A "position" is the board <b>before</b> a turn; STM = that turn's player. Consumers evaluate
 * it with {@link #MOVES_LEFT} = 3 (fresh turn start — a fixed assumption; the real per-ply
 * movesLeft is not reconstructed from the PGN — but within a turn the state IS threaded, so the
 * turn-scoped legality rules apply). Moves are applied through {@link GoState#apply}, the faithful
 * port of the server's {@code state.go} transition — NOT {@code SearchEngine.applyAction}, which
 * diverges from the real rules in two ways that corrupt a replay: it never fortifies a captured
 * cell, and it erases cells that lose base-connectivity. Both are contradicted by games.db itself:
 * 521 recorded {@code attack} moves (in 177 of the 217 replayable 12x12 games, 9749 moves) target a
 * cell the erasing variant has already emptied, while the {@code GoState} rules replay every
 * recorded move consistently. Counts measured 2026-07-25 against {@code /home/iv/games.db}, which
 * is refreshed out-of-band — the two rules themselves are pinned by {@code GamesDbReplayTest}.
 *
 * <p>A game yields a skip reason instead of snapshots when a turn has no {@code player} ({@code
 * no_player}), a player outside 1..2 ({@code multiplayer} — the replay only models the 1v1 rules),
 * a recorded action the rules reject ({@code illegal_move}), or replay throws ({@code
 * replay_error:NullPointerException}, the exception class named so a parser bug cannot hide in one
 * anonymous bucket).
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
        JsonNode playerNode = field(turn, "player");
        if (playerNode == null) {
          return new Replay(null, "no_player");
        }
        int player = playerNode.asInt();
        if (player < 1 || player > 2) {
          return new Replay(null, "multiplayer");
        }

        snaps.add(new Snapshot(board, player, neutralUsed.clone()));

        JsonNode moves = field(turn, "moves");
        if (moves != null && moves.isArray()) {
          GoState state = applyTurn(board, player, moves, neutralUsed);
          if (state == null) {
            return new Replay(null, "illegal_move");
          }
          board = state.toBoard();
          // The threaded state owns the neutral budget (mutate sets it); mirror it out for the
          // snapshots of later turns.
          for (int p = 1; p <= 2; p++) {
            neutralUsed[p - 1] = state.neutralUsed(p);
          }
          if (state.gameOver()) {
            // Stop snapshotting once a base falls. A recorded turn AFTER game over is rejected by
            // applyTurn only if it carries moves — Go's omitempty drops an empty slice, so a
            // {"player":N} turn with no moves would otherwise add a snapshot of a decided board,
            // and HandTunedEval scores that ±MATE_SCORE/2 (5e8) against a corpus that spans ±3e4.
            // One such row silently dominates the least-squares fit and every mean_eval.
            break;
          }
        }
      }
      return new Replay(snaps, null);
    } catch (Exception e) {
      // Name the exception in the reason: a blanket "replay_error" bucket hid a parser NPE that was
      // silently dropping games (see parseAction), and the skip counts are what the v3 gate doc
      // asks the reader to trust.
      return new Replay(null, "replay_error:" + e.getClass().getSimpleName());
    }
  }

  /**
   * Replay one recorded turn's moves under the real rules — the single legality-checked turn
   * transition every games.db consumer uses (the miners via {@link #replay}, the live retrainer via
   * {@link GameImporter}).
   *
   * <p>ONE state is threaded through the whole turn, so {@code movesLeft} actually decrements and
   * the turn-scoped rules bite: at most {@link GoState#ACTIONS_PER_TURN} actions, and a neutral
   * pair only as the turn's opening action ({@code GoState.legalAction} gates it on {@code
   * movesLeft}). Rebuilding the state per move (with {@code movesLeft} reset to 3) replayed both of
   * those as legal. Moves go through the legality-checked {@link GoState#apply}, not {@code
   * applyGenerated}: an out-of-rules recorded move must surface to the caller rather than silently
   * fabricate a board that features are then computed from. Rejects 0 of the 9749 moves in the
   * current corpus.
   *
   * @return the state after the turn, or {@code null} if the rules reject it — a recorded action
   *     after the turn is spent (3 actions, or a neutral placement, which ends the turn) or after
   *     the game ends belongs to a turn that cannot exist, and counts as a rejection
   * @throws IllegalArgumentException if a move node is not a parseable action
   */
  static GoState applyTurn(Board board, int player, JsonNode moves, boolean[] neutralUsed) {
    GoState state = GoState.fromBoard(board, player, GoState.ACTIONS_PER_TURN, neutralUsed);
    for (JsonNode mv : moves) {
      if (state.gameOver() || state.currentPlayer() != player) {
        return null;
      }
      GoState next = state.apply(parseAction(mv));
      if (next == null) {
        return null;
      }
      state = next;
    }
    return state;
  }

  /** Empty board with the two bases in opposite corners. */
  static Board initialBoard(int rows, int cols) {
    Board board = new Board(rows, cols);
    board.setCell(0, 0, new Cell(1, CellKind.BASE));
    board.setCell(rows - 1, cols - 1, new Cell(2, CellKind.BASE));
    return board;
  }

  /** Parse one stored move node into an engine action. */
  private static Action parseAction(JsonNode move) {
    JsonNode typeNode = field(move, "type");
    if (typeNode == null) {
      throw new IllegalArgumentException("move has no type");
    }
    String type = typeNode.asText().toLowerCase(Locale.ROOT);
    if ("place".equals(type) || "attack".equals(type) || "move".equals(type)) {
      return new MoveAction(pos(move));
    }
    if ("neutral".equals(type) || "neutrals".equals(type)) {
      JsonNode cells = field(move, "cells");
      if (cells == null || !cells.isArray() || cells.size() != 2) {
        throw new IllegalArgumentException("neutral action must contain exactly two cells");
      }
      return new PlaceNeutralsAction(pos(cells.get(0)), pos(cells.get(1)));
    }
    throw new IllegalArgumentException("Unsupported move type: " + type);
  }

  /**
   * Coordinate pair from a stored node. An absent {@code row}/{@code col} means 0: the recorder
   * marshals with Go's {@code omitempty}, which DROPS zero-valued ints, so row 0 and column 0 are
   * simply missing from the JSON ({@code {"type":"place","col":1}} is (0,1)). Reading them as a
   * null node threw and discarded the whole game as {@code replay_error} — 15 such moves across 5
   * of the recorded 12x12 games, all of which replay legally once the zero is restored.
   */
  private static Pos pos(JsonNode node) {
    return new Pos(coord(node, "row"), coord(node, "col"));
  }

  private static int coord(JsonNode node, String name) {
    JsonNode value = field(node, name);
    return value == null ? 0 : value.asInt();
  }

  /**
   * Stored field, tolerating the capitalized keys Go's default struct marshaling emits ({@code
   * Player}/{@code Moves}/{@code Type}/{@code Row}/{@code Col}/{@code Cells}); the live DB uses the
   * lowercase form.
   */
  private static JsonNode field(JsonNode node, String name) {
    JsonNode value = node.get(name);
    return value != null
        ? value
        : node.get(Character.toUpperCase(name.charAt(0)) + name.substring(1));
  }
}
