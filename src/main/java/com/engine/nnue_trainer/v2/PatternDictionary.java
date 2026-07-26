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
 * Loads the mined promoted-pattern dictionary (python/v2/nnue_v2_dictionary.json) for signature ->
 * feature-id lookup. Unseen signatures are a miss (-1) per spec.
 */
public class PatternDictionary {

  private final Map<String, Integer> patternToId;
  private final int numPatterns;
  private final int minCount;
  private final int version;

  private PatternDictionary(
      Map<String, Integer> patternToId, int numPatterns, int minCount, int version) {
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

    JsonNode patterns = root.path("pattern_to_id");
    JsonNode meta = root.path("metadata");
    if (!patterns.isObject() || !meta.isObject()) {
      throw new IOException("dictionary needs an object pattern_to_id and metadata");
    }

    Map<String, Integer> map = new HashMap<>();
    int maxId = -1;
    for (Iterator<Map.Entry<String, JsonNode>> it = patterns.fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> e = it.next();
      int id = e.getValue().asInt();
      map.put(e.getKey(), id);
      maxId = Math.max(maxId, id);
    }

    // Ids index straight into the weight matrix, whose length is validated against numPatterns.
    // A metadata/map disagreement would pass construction and then throw AIOOBE mid-evaluation.
    int numPatterns = meta.path("num_patterns").asInt(-1);
    if (numPatterns != map.size() || maxId != numPatterns - 1) {
      throw new IOException(
          "dictionary metadata says num_patterns="
              + numPatterns
              + " but pattern_to_id holds "
              + map.size()
              + " entries with max id "
              + maxId);
    }
    return new PatternDictionary(
        map, numPatterns, meta.path("min_count").asInt(), meta.path("version").asInt());
  }

  /**
   * @return the feature id for the signature, or -1 on miss (unseen pattern).
   */
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
