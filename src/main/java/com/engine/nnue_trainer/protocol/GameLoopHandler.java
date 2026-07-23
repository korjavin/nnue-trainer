package com.engine.nnue_trainer.protocol;

import com.engine.nnue_trainer.board.Action;
import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.board.MoveAction;
import com.engine.nnue_trainer.board.PlaceNeutralsAction;
import com.engine.nnue_trainer.search.SearchEngine;
import com.engine.nnue_trainer.search.SearchResult;
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
        JsonNode neutralNode = snapshot.get("neutralUsed");
        boolean canPlaceNeutral = !neutralNode.get(myPlayerIndex - 1).asBoolean();
        // Feed the hand-tuned eval the root turn's non-board state (no-op for NNUE).
        boolean[] neutralUsed = new boolean[neutralNode.size()];
        for (int i = 0; i < neutralNode.size(); i++) {
          neutralUsed[i] = neutralNode.get(i).asBoolean();
        }
        searchEngine.setHandTunedState(movesLeft, neutralUsed);
        makeMove(board, canPlaceNeutral);
      }
    }
  }

  private Board parseBoardFromSnapshot(JsonNode snapshot) {
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

  private void makeMove(Board board, boolean canPlaceNeutral) {
    SearchResult searchResult =
        searchEngine.findBestActionWithTimeLimitUsingModel(
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

      if (bestAction instanceof MoveAction) {
        MoveAction move = (MoveAction) bestAction;
        response.put("type", "move");
        response.put("row", move.target.row);
        response.put("col", move.target.col);
        System.out.println("Playing Move: (" + move.target.row + ", " + move.target.col + ")");
      } else if (bestAction instanceof PlaceNeutralsAction) {
        PlaceNeutralsAction place = (PlaceNeutralsAction) bestAction;
        response.put("type", "neutrals");
        JsonNode cellsNode =
            objectMapper
                .createArrayNode()
                .add(
                    objectMapper
                        .createObjectNode()
                        .put("row", place.pos1.row)
                        .put("col", place.pos1.col))
                .add(
                    objectMapper
                        .createObjectNode()
                        .put("row", place.pos2.row)
                        .put("col", place.pos2.col));
        response.set("cells", cellsNode);
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
}
