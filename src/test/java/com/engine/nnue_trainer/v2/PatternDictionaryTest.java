package com.engine.nnue_trainer.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

public class PatternDictionaryTest {

  private static final Path DICT = Path.of("python", "v2", "nnue_v2_dictionary.json");

  @Test
  public void testSizeMatchesMetadata() throws Exception {
    PatternDictionary dict = PatternDictionary.load(DICT);
    assertTrue(dict.size() > 0);
    assertEquals(dict.numPatterns(), dict.size());
  }

  @Test
  public void testKnownSignatureMapsToId() throws Exception {
    PatternDictionary dict = PatternDictionary.load(DICT);
    // Committed entry from nnue_v2_dictionary.json.
    String known = "0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,4|7";
    assertTrue(dict.contains(known));
    assertEquals(97, dict.lookup(known));
  }

  @Test
  public void testUnseenSignatureMisses() throws Exception {
    PatternDictionary dict = PatternDictionary.load(DICT);
    assertEquals(-1, dict.lookup("this-is-not-a-real-signature|7"));
  }
}
