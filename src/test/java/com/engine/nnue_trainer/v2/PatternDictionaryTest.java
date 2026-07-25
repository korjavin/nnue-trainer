package com.engine.nnue_trainer.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    // The loader must agree with the committed file. The id itself is NOT pinned: re-mining the
    // dictionary renumbers every pattern, and a hardcoded id silently rots into a false failure
    // (it did — this asserted 97 while the re-mined dict says 10). Read the expectation from the
    // file so the test keeps checking the loader, not a stale snapshot of the mining run.
    String known = "0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,4|7";
    int expected =
        new ObjectMapper().readTree(DICT.toFile()).get("pattern_to_id").get(known).asInt();
    assertTrue(dict.contains(known));
    assertEquals(expected, dict.lookup(known));
  }

  @Test
  public void testUnseenSignatureMisses() throws Exception {
    PatternDictionary dict = PatternDictionary.load(DICT);
    assertEquals(-1, dict.lookup("this-is-not-a-real-signature|7"));
  }
}
