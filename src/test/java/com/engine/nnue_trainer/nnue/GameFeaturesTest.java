package com.engine.nnue_trainer.nnue;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

public class GameFeaturesTest {

  @Test
  public void testFlattenSize() {
    GameFeatures features = new GameFeatures();
    float[] flattened = features.flatten();
    assertEquals(104, flattened.length, "Flattened array should have size 104");
  }

  @Test
  public void testFlattenValues() {
    GameFeatures features = new GameFeatures();

    // Set some dummy values
    features.players[0].normal = 1.0f;
    features.players[0].severableFrac = 2.0f;
    features.players[1].normal = 3.0f;
    features.players[1].fortified = 4.0f;
    features.players[3].chainReach = 5.0f;

    float[] flattened = features.flatten();

    // Check values based on flattened indices
    assertEquals(1.0f, flattened[0], "Player 0 normal feature should be at index 0");
    assertEquals(2.0f, flattened[25], "Player 0 severableFrac feature should be at index 25");
    assertEquals(3.0f, flattened[26], "Player 1 normal feature should be at index 26");
    assertEquals(4.0f, flattened[27], "Player 1 fortified feature should be at index 27");
    assertEquals(5.0f, flattened[102], "Player 3 chainReach feature should be at index 102");
  }
}
