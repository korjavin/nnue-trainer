package com.engine.nnue_trainer.protocol;

import com.engine.nnue_trainer.board.Action;
import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.board.MoveAction;
import com.engine.nnue_trainer.board.PlaceNeutralsAction;
import com.engine.nnue_trainer.search.SearchEngine;
import com.engine.nnue_trainer.search.SearchResult;
import com.engine.nnue_trainer.search.gobot.GoBotSearcher;
import com.engine.nnue_trainer.search.gobot.GoResult;
import com.engine.nnue_trainer.search.gobot.GoState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class GameLoopHandler {
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final MessageSender messageSender;
  private final SearchEngine searchEngine;
  private int myPlayerIndex = -1;
  private String currentGameId = "";

  public GameLoopHandler(MessageSender messageSender) {
    this(messageSender, new SearchEngine());
  }

  public GameLoopHandler(MessageSender messageSender, SearchEngine searchEngine) {
    this.messageSender = messageSender;
    this.searchEngine = searchEngine;
  }

  public void handleMessage(String jsonMessage) {
    try {
      JsonNode node = objectMapper.readTree(jsonMessage);
      if (!node.has("type")) return;
      String type = node.get("type").asText();

      if ("multiplayer_game_start".equals(type) || "game_start".equals(type)) {
        this.currentGameId = node.get("gameId").asText();
        this.myPlayerIndex = node.get("yourPlayer").asInt();
        System.out.println("Game started: gameId=" + currentGameId + ", myPlayer=" + myPlayerIndex);
        // game_start carries the initial snapshot; player 1 must move off it to start play.
        handleSnapshot(node);
      } else if ("move_made".equals(type)) {
        int player = node.get("player").asInt();
        if (player != myPlayerIndex) {
          int row = node.get("row").asInt();
          int col = node.get("col").asInt();
          System.out.println("Opponent played Move at (" + row + ", " + col + ")");
        }
        handleSnapshot(node);
      } else if ("neutrals_placed".equals(type)) {
        int player = node.get("player").asInt();
        if (player != myPlayerIndex) {
          JsonNode cells = node.get("cells");
          if (cells != null && cells.isArray() && cells.size() >= 2) {
            System.out.println(
                "Opponent placed Neutrals at "
                    + "("
                    + cells.get(0).get("row").asInt()
                    + ", "
                    + cells.get(0).get("col").asInt()
                    + ") and "
                    + "("
                    + cells.get(1).get("row").asInt()
                    + ", "
                    + cells.get(1).get("col").asInt()
                    + ")");
          } else {
            System.out.println("Opponent placed Neutrals");
          }
        }
        handleSnapshot(node);
      } else if ("turn_change".equals(type)) {
        if (node.has("snapshot")) {
          JsonNode snapshot = node.get("snapshot");
          int currentPlayer = snapshot.get("currentPlayer").asInt();
          int movesLeft = snapshot.get("movesLeft").asInt();
          System.out.println(
              "Turn changed: Player " + currentPlayer + "'s turn (Moves left: " + movesLeft + ")");
        }
        handleSnapshot(node);
      } else if ("game_end".equals(type)) {
        System.out.println("Game ended. Winner: player " + node.get("winner").asInt());
        this.currentGameId = "";
        this.myPlayerIndex = -1;
      }
    } catch (Exception e) {
      System.err.println("Error in GameLoopHandler: " + e.getMessage());
      e.printStackTrace();
    }
  }

  private void handleSnapshot(JsonNode node) {
    if (node.has("snapshot")) {
      JsonNode snapshot = node.get("snapshot");
      int currentPlayer = snapshot.get("currentPlayer").asInt();
      boolean gameOver = snapshot.get("gameOver").asBoolean();
      int movesLeft = snapshot.has("movesLeft") ? snapshot.get("movesLeft").asInt() : 0;

      if (!gameOver && currentPlayer == myPlayerIndex && movesLeft > 0) {
        Board board = parseBoardFromSnapshot(snapshot);
        boolean[] neutralUsed = parseNeutralUsed(snapshot);
        boolean canPlaceNeutral = !neutralUsed[myPlayerIndex - 1];
        // Feed the hand-tuned eval the root turn's non-board state (no-op for NNUE).
        searchEngine.setHandTunedState(movesLeft, neutralUsed);
        makeMove(snapshot, board, canPlaceNeutral, neutralUsed);
      }
    }
  }

  /** Parse the per-player {@code neutralUsed} flags (length = player count) from a snapshot. */
  static boolean[] parseNeutralUsed(JsonNode snapshot) {
    JsonNode neutralNode = snapshot.get("neutralUsed");
    boolean[] neutralUsed = new boolean[neutralNode.size()];
    for (int i = 0; i < neutralNode.size(); i++) {
      neutralUsed[i] = neutralNode.get(i).asBoolean();
    }
    return neutralUsed;
  }

  /**
   * Build the search {@link GoState} from a server snapshot with the SAME inputs the live GOBOT
   * path feeds {@link GoState#fromBoard} (board orientation, current player, movesLeft, per-player
   * neutralUsed). The live GOBOT path ({@link #gobotSearch}) builds through this method, and the
   * {@code handleSnapshot} guard pins {@code snapshot.currentPlayer} equal to {@code
   * myPlayerIndex}; so this is the single construction point the parity oracle ({@code
   * GoStateFromSnapshotTest}) asserts against.
   */
  static GoState goStateFromSnapshot(JsonNode snapshot) {
    int currentPlayer = snapshot.get("currentPlayer").asInt();
    int movesLeft = snapshot.has("movesLeft") ? snapshot.get("movesLeft").asInt() : 0;
    return GoState.fromBoard(
        parseBoardFromSnapshot(snapshot), currentPlayer, movesLeft, parseNeutralUsed(snapshot));
  }

  private static Board parseBoardFromSnapshot(JsonNode snapshot) {
    int rows = snapshot.get("rows").asInt();
    int cols = snapshot.get("cols").asInt();
    Board board = new Board(rows, cols);
    JsonNode boardNode = snapshot.get("board");
    for (int r = 0; r < rows; r++) {
      JsonNode rowNode = boardNode.get(r);
      for (int c = 0; c < cols; c++) {
        JsonNode cellNode = rowNode.get(c);

        JsonNode ownerNode = cellNode.has("owner") ? cellNode.get("owner") : cellNode.get("Owner");
        JsonNode kindNode = cellNode.has("kind") ? cellNode.get("kind") : cellNode.get("Kind");

        int owner = ownerNode != null ? ownerNode.asInt() : 0;
        CellKind kind = CellKind.EMPTY;

        if (kindNode != null) {
          String kindStr = kindNode.asText().toUpperCase();
          try {
            kind = CellKind.valueOf(kindStr);
          } catch (IllegalArgumentException e) {
            try {
              int val = Integer.parseInt(kindStr);
              for (CellKind k : CellKind.values()) {
                if (k.value == val) {
                  kind = k;
                  break;
                }
              }
            } catch (NumberFormatException nfe) {
              // Ignore
            }
          }
        }
        board.setCell(r, c, new Cell(owner, kind));
      }
    }
    return board;
  }

  // SEARCH=GOBOT selects the ported GoBot search (book -> iterative-deepening minimax -> HandTuned
  // leaf); with EVAL=HANDTUNED that is a GoBot clone by construction. Mirrors EVAL detection.
  // Read at construction (not class-load) so the SEARCH flag is honoured per instance and testable.
  private final boolean useGobotSearch = gobotSearchFromEnv();

  // Per-move node budget for the deterministic live GoBot search. ~GoBot's 1s worth of nodes and
  // then some (GoBot does ~17-55k/move); at 60k the clone beats GoBot 6-0. Overridable via env.
  private static final long DEFAULT_LIVE_NODE_LIMIT = 60000L;

  private static boolean gobotSearchFromEnv() {
    // Default to the STRONGEST config (GoBot search + hand-tuned leaf = beats GoBot 6-0) with no
    // env needed — production must always run the strongest by default. Opt out with SEARCH=NEGAMAX
    // (or any non-GOBOT value) to use the legacy negamax NNUE search.
    String v = System.getProperty("SEARCH", System.getenv("SEARCH"));
    return v == null || v.isBlank() || "GOBOT".equalsIgnoreCase(v);
  }

  // EVAL=NNUE (with SEARCH=GOBOT) swaps the GoBot search's leaf eval to the learned NNUE net;
  // EVAL=HANDTUNED / unset keeps the hand-tuned leaf (the GoBot clone). Mirrors SearchEngine's EVAL
  // flag detection. Configured once at class load so every static GoBot entry point picks it up.
  static {
    if (gobotLeafEvalFor(
            System.getProperty("SEARCH", System.getenv("SEARCH")),
            System.getProperty("EVAL", System.getenv("EVAL")))
        == GoBotSearcher.LeafEval.NNUE) {
      GoBotSearcher.configureDefaultLeafEval(
          GoBotSearcher.LeafEval.NNUE, com.engine.nnue_trainer.nnue.NNUEModel.createDefault());
    }
  }

  /** Pure flag resolution: NNUE leaf only when {@code SEARCH=GOBOT} and {@code EVAL=NNUE}. */
  static GoBotSearcher.LeafEval gobotLeafEvalFor(String searchFlag, String evalFlag) {
    if ("GOBOT".equalsIgnoreCase(searchFlag) && "NNUE".equalsIgnoreCase(evalFlag)) {
      return GoBotSearcher.LeafEval.NNUE;
    }
    return GoBotSearcher.LeafEval.HAND_TUNED;
  }

  /**
   * Translate a chosen {@link Action} into the server move message, the sole tested translation
   * point. Mirrors GoBot's {@code actionMessage} (bot_client.go): a {@link MoveAction} sends {@code
   * {type:"move", row, col}} (the server infers grow vs attack from the board); a {@link
   * PlaceNeutralsAction} sends {@code {type:"neutrals", cells:[{row,col},{row,col}]}}.
   */
  static void writeAction(ObjectNode response, ObjectMapper mapper, Action action) {
    if (action instanceof MoveAction) {
      MoveAction move = (MoveAction) action;
      response.put("type", "move");
      response.put("row", move.target.row);
      response.put("col", move.target.col);
    } else if (action instanceof PlaceNeutralsAction) {
      PlaceNeutralsAction place = (PlaceNeutralsAction) action;
      response.put("type", "neutrals");
      response.set(
          "cells",
          mapper
              .createArrayNode()
              .add(mapper.createObjectNode().put("row", place.pos1.row).put("col", place.pos1.col))
              .add(
                  mapper.createObjectNode().put("row", place.pos2.row).put("col", place.pos2.col)));
    }
  }

  private void makeMove(
      JsonNode snapshot, Board board, boolean canPlaceNeutral, boolean[] neutralUsed) {
    SearchResult searchResult =
        useGobotSearch
            ? gobotSearch(snapshot, neutralUsed)
            : searchEngine.findBestActionWithTimeLimitUsingModel(
                board, myPlayerIndex, 5000, canPlaceNeutral);
    Action bestAction = searchResult.bestAction;

    System.out.println(
        "[SEARCH] depth="
            + searchResult.depth
            + " nodes="
            + searchResult.nodesEvaluated
            + " timeMs="
            + searchResult.timeMs
            + " score="
            + searchResult.score);

    if (bestAction == null) {
      System.out.println("No legal actions available.");
      return;
    }

    try {
      ObjectNode response = objectMapper.createObjectNode();
      response.put("gameId", currentGameId);

      // Append diagnostics payload
      double scoreToSend = searchResult.score;
      if (scoreToSend == Float.POSITIVE_INFINITY) {
        scoreToSend = 1000000.0;
      } else if (scoreToSend == Float.NEGATIVE_INFINITY) {
        scoreToSend = -1000000.0;
      }
      response.put("score", scoreToSend * 1000.0);
      response.put("depth", searchResult.depth);
      response.put("nodesEvaluated", searchResult.nodesEvaluated);
      response.put("timeMs", searchResult.timeMs);

      writeAction(response, objectMapper, bestAction);
      if (bestAction instanceof MoveAction) {
        MoveAction move = (MoveAction) bestAction;
        System.out.println("Playing Move: (" + move.target.row + ", " + move.target.col + ")");
      } else if (bestAction instanceof PlaceNeutralsAction) {
        PlaceNeutralsAction place = (PlaceNeutralsAction) bestAction;
        System.out.println(
            "Placing Neutrals: ("
                + place.pos1.row
                + ", "
                + place.pos1.col
                + "), ("
                + place.pos2.row
                + ", "
                + place.pos2.col
                + ")");
      }

      messageSender.send(objectMapper.writeValueAsString(response));
    } catch (Exception e) {
      System.err.println("Failed to send action: " + e.getMessage());
    }
  }

  /** Run the ported GoBot search and adapt its {@link GoResult} into a {@link SearchResult}. */
  private SearchResult gobotSearch(JsonNode snapshot, boolean[] neutralUsed) {
    long start = System.currentTimeMillis();
    // GoState.fromBoard builds a 1v1 (players 1,2) state — the only mode SEARCH=GOBOT supports.
    // neutralUsed is per-player, so its length is the game's player count. Anything above 2 would
    // yield a state where player 3/4 is inactive (silent forfeit), so refuse loudly instead.
    if (neutralUsed != null && neutralUsed.length > 2) {
      System.err.println(
          "SEARCH=GOBOT supports 1v1 only; got " + neutralUsed.length + " players — no move made.");
      return new SearchResult(null, 0, 0, 0, System.currentTimeMillis() - start);
    }
    // Build the live GoState through the same tested seam GoStateFromSnapshotTest asserts against
    // (handleSnapshot pins snapshot.currentPlayer == myPlayerIndex).
    // Live search uses the DETERMINISTIC, parity-verified node-budget entry by default: the
    // time-based choose() had a wall-clock-deadline move-selection bug (bd 0dj.7) that lost 0-10
    // vs GoBot, while chooseNodeBudget(60k) wins 6-0. Overridable via env for experiments.
    GoState gs = goStateFromSnapshot(snapshot);
    GoResult r;
    String fd = System.getenv("GOBOT_FIXED_DEPTH");
    String nl = System.getenv("GOBOT_NODE_LIMIT");
    String tm = System.getenv("GOBOT_TIME_MODE"); // opt back into the (buggy) time-based choose()
    if (fd != null && !fd.isBlank()) {
      r = GoBotSearcher.chooseDepth(gs, Integer.parseInt(fd.trim()));
    } else if (tm != null && !tm.isBlank()) {
      r = GoBotSearcher.choose(gs);
    } else {
      long limit =
          (nl != null && !nl.isBlank()) ? Long.parseLong(nl.trim()) : DEFAULT_LIVE_NODE_LIMIT;
      r = GoBotSearcher.chooseNodeBudget(gs, limit);
    }
    if (r == null) {
      // No legal action from this position; let makeMove log "No legal actions available."
      return new SearchResult(null, 0, 0, 0, System.currentTimeMillis() - start);
    }
    return new SearchResult(
        r.action, r.score, r.depth, (int) r.nodes, System.currentTimeMillis() - start);
  }
}
