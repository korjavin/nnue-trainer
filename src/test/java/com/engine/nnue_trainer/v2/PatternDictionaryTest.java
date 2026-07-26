package com.engine.nnue_trainer.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    // Committed entry from nnue_v2_dictionary.json. The id is read from the JSON rather than
    // hardcoded: ids are reassigned whenever the dictionary is re-mined, and a literal here rots
    // into a red test that says nothing about the loader.
    String known = "0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,4|7";
    JsonNode raw = new ObjectMapper().readTree(DICT.toFile()).get("pattern_to_id").get(known);
    assertTrue(raw != null, "signature missing from the committed dictionary");
    assertTrue(dict.contains(known));
    assertEquals(raw.asInt(), dict.lookup(known));
  }

  @Test
  public void testUnseenSignatureMisses() throws Exception {
    PatternDictionary dict = PatternDictionary.load(DICT);
    assertEquals(-1, dict.lookup("this-is-not-a-real-signature|7"));
  }
}
