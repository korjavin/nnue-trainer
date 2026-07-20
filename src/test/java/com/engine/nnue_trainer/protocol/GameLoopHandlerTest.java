package com.engine.nnue_trainer.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GameLoopHandlerTest {

    private GameLoopHandler handler;
    private ObjectMapper objectMapper;
    private boolean makeMoveCalled;
    private boolean updateLocalBoardCalled;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        makeMoveCalled = false;
        updateLocalBoardCalled = false;

        handler = new GameLoopHandler(objectMapper) {
            @Override
            protected void makeMove() {
                makeMoveCalled = true;
            }

            @Override
            protected void updateLocalBoard(com.fasterxml.jackson.databind.JsonNode node) {
                updateLocalBoardCalled = true;
            }
        };
    }

    @Test
    void testMultiplayerGameStart() {
        String msg = "{\"type\":\"multiplayer_game_start\",\"playerIndex\":1,\"rows\":6,\"cols\":7}";
        handler.handleMessage(msg);

        assertFalse(handler.isIdle());
        assertEquals(1, handler.getMyPlayerIndex());
        assertEquals(6, handler.getRows());
        assertEquals(7, handler.getCols());
    }

    @Test
    void testTurnChangeTriggersMakeMove() {
        // First start the game as player 0
        handler.handleMessage("{\"type\":\"multiplayer_game_start\",\"playerIndex\":0,\"rows\":6,\"cols\":7}");

        // Then turn changes to player 0
        handler.handleMessage("{\"type\":\"turn_change\",\"player\":0}");
        assertTrue(makeMoveCalled, "makeMove should have been called when turn changes to our player.");

        // Reset and test not our turn
        makeMoveCalled = false;
        handler.handleMessage("{\"type\":\"turn_change\",\"player\":1}");
        assertFalse(makeMoveCalled, "makeMove should NOT be called when turn changes to another player.");
    }

    @Test
    void testMoveMade() {
        handler.handleMessage("{\"type\":\"move_made\",\"col\":3,\"row\":5,\"player\":1}");
        assertTrue(updateLocalBoardCalled, "updateLocalBoard should be called on move_made.");
    }

    @Test
    void testGameEnd() {
        handler.handleMessage("{\"type\":\"multiplayer_game_start\",\"playerIndex\":1,\"rows\":6,\"cols\":7}");
        assertFalse(handler.isIdle());

        handler.handleMessage("{\"type\":\"game_end\"}");
        assertTrue(handler.isIdle(), "Handler should be idle after game_end.");
        assertEquals(-1, handler.getMyPlayerIndex(), "Player index should be reset.");
        assertEquals(-1, handler.getRows(), "Rows should be reset.");
        assertEquals(-1, handler.getCols(), "Cols should be reset.");
    }
}
