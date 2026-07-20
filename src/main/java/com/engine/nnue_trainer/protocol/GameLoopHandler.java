package com.engine.nnue_trainer.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GameLoopHandler {
    private static final Logger logger = LoggerFactory.getLogger(GameLoopHandler.class);
    private final ObjectMapper objectMapper;

    private boolean isIdle = true;
    private int myPlayerIndex = -1;
    private int rows = -1;
    private int cols = -1;

    public GameLoopHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void handleMessage(String message) {
        try {
            JsonNode rootNode = objectMapper.readTree(message);
            if (!rootNode.has("type")) {
                return;
            }

            String type = rootNode.get("type").asText();
            switch (type) {
                case "multiplayer_game_start":
                    handleMultiplayerGameStart(rootNode);
                    break;
                case "turn_change":
                    handleTurnChange(rootNode);
                    break;
                case "move_made":
                    handleMoveMade(rootNode);
                    break;
                case "game_end":
                    handleGameEnd(rootNode);
                    break;
                default:
                    // Not a game loop message or unhandled
                    break;
            }
        } catch (Exception e) {
            logger.error("Failed to parse message: {}", message, e);
        }
    }

    private void handleMultiplayerGameStart(JsonNode node) {
        if (node.has("playerIndex")) {
            this.myPlayerIndex = node.get("playerIndex").asInt();
        } else if (node.has("player")) {
            this.myPlayerIndex = node.get("player").asInt();
        }

        if (node.has("rows")) {
            this.rows = node.get("rows").asInt();
        }
        if (node.has("cols")) {
            this.cols = node.get("cols").asInt();
        }

        this.isIdle = false;
        logger.info("Game started: playerIndex={}, rows={}, cols={}", myPlayerIndex, rows, cols);
    }

    private void handleTurnChange(JsonNode node) {
        if (node.has("player")) {
            int currentPlayer = node.get("player").asInt();
            if (currentPlayer == this.myPlayerIndex) {
                makeMove();
            }
        }
    }

    private void handleMoveMade(JsonNode node) {
        updateLocalBoard(node);
    }

    private void handleGameEnd(JsonNode node) {
        this.isIdle = true;
        this.myPlayerIndex = -1;
        this.rows = -1;
        this.cols = -1;
        logger.info("Game ended, returning to idle state.");
    }

    // These methods would interact with the board/engine
    protected void makeMove() {
        logger.info("Making move...");
        // To be implemented or overridden
    }

    protected void updateLocalBoard(JsonNode node) {
        logger.info("Updating local board...");
        // To be implemented or overridden
    }

    public boolean isIdle() {
        return isIdle;
    }

    public int getMyPlayerIndex() {
        return myPlayerIndex;
    }

    public int getRows() {
        return rows;
    }

    public int getCols() {
        return cols;
    }
}
