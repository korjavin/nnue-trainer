package com.engine.nnue_trainer.v2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Loads the mined promoted-pattern dictionary (python/v2/nnue_v2_dictionary.json)
 * for signature -> feature-id lookup. Unseen signatures are a miss (-1) per spec.
 */
public class PatternDictionary {

  private final Map<String, Integer> patternToId;
  private final int numPatterns;
  private final int minCount;
  private final int version;

  private PatternDictionary(Map<String, Integer> patternToId, int numPatterns, int minCount,
      int version) {
    this.patternToId = patternToId;
    this.numPatterns = numPatterns;
    this.minCount = minCount;
    this.version = version;
  }

  public static PatternDictionary load(Path path) throws IOException {
    try (InputStream in = Files.newInputStream(path)) {
      return load(in);
    }
  }

  public static PatternDictionary load(InputStream in) throws IOException {
    JsonNode root = new ObjectMapper().readTree(in);

    Map<String, Integer> map = new HashMap<>();
    JsonNode patterns = root.get("pattern_to_id");
    for (Iterator<Map.Entry<String, JsonNode>> it = patterns.fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> e = it.next();
      map.put(e.getKey(), e.getValue().asInt());
    }

    JsonNode meta = root.get("metadata");
    return new PatternDictionary(map, meta.get("num_patterns").asInt(),
        meta.get("min_count").asInt(), meta.get("version").asInt());
  }

  /** @return the feature id for the signature, or -1 on miss (unseen pattern). */
  public int lookup(String signature) {
    return patternToId.getOrDefault(signature, -1);
  }

  public boolean contains(String signature) {
    return patternToId.containsKey(signature);
  }

  public int size() {
    return patternToId.size();
  }

  public int numPatterns() {
    return numPatterns;
  }

  public int minCount() {
    return minCount;
  }

  public int version() {
    return version;
  }
}
