package com.engine.nnue_trainer.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

public class PatternDictionaryTest {

  @Test
  public void testLoadAndLookup() throws IOException {
    String json =
        """
        {
          "pattern_to_id": {
            "0,0,0,0,0,0,0,0,0,0,0,0,4,0,0,0,0,0,0,0,0,0,0,0,0:7": 0,
            "1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1:3": 1
          },
          "metadata": {
            "num_patterns": 2,
            "min_count": 5,
            "version": 2
          }
        }
        """;

    PatternDictionary dictionary =
        PatternDictionary.load(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));

    assertNotNull(dictionary);
    assertEquals(2, dictionary.getNumPatterns());

    int[] symbols0 = new int[25];
    symbols0[12] = 4; // 0,0,0,0,0,0,0,0,0,0,0,0,4,0,0,0,0,0,0,0,0,0,0,0,0
    int id0 = dictionary.getFeatureId(symbols0, 7);
    assertEquals(0, id0);

    int[] symbols1 = new int[25];
    for (int i = 0; i < 25; i++) {
      symbols1[i] = 1;
    }
    int id1 = dictionary.getFeatureId(symbols1, 3);
    assertEquals(1, id1);

    int idMiss = dictionary.getFeatureId(symbols0, 3); // different distance bucket
    assertEquals(-1, idMiss);
  }
}
