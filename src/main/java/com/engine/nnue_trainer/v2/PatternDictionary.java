package com.engine.nnue_trainer.v2;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

public class PatternDictionary {
  private final Map<String, Integer> patternToId;
  private final Metadata metadata;

  public PatternDictionary(Map<String, Integer> patternToId, Metadata metadata) {
    this.patternToId = patternToId;
    this.metadata = metadata;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class DictionaryData {
    @JsonProperty("pattern_to_id")
    public Map<String, Integer> patternToId;

    @JsonProperty("metadata")
    public Metadata metadata;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Metadata {
    @JsonProperty("num_patterns")
    public int numPatterns;

    @JsonProperty("min_count")
    public int minCount;

    @JsonProperty("version")
    public int version;
  }

  public static PatternDictionary load(InputStream in) throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    DictionaryData data = mapper.readValue(in, DictionaryData.class);
    return new PatternDictionary(data.patternToId, data.metadata);
  }

  public static PatternDictionary load(File file) throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    DictionaryData data = mapper.readValue(file, DictionaryData.class);
    return new PatternDictionary(data.patternToId, data.metadata);
  }

  private String buildSignature(int[] symbols, int distanceBucket) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < symbols.length; i++) {
      sb.append(symbols[i]);
      if (i < symbols.length - 1) {
        sb.append(",");
      }
    }
    sb.append(":").append(distanceBucket);
    return sb.toString();
  }

  public int getFeatureId(int[] symbols, int distanceBucket) {
    String signature = buildSignature(symbols, distanceBucket);
    return patternToId.getOrDefault(signature, -1);
  }

  public int getNumPatterns() {
    return metadata != null ? metadata.numPatterns : 0;
  }
}
