package com.engine.nnue_trainer.train;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The committed v3 gate preview page must stay self-contained (no network at all) and in sync with
 * the committed mined stats it inlines.
 */
public class V3FeaturePreviewHtmlTest {

  private static final Path HTML = Path.of("docs/nnue-v3-feature-preview.html");
  private static final Path STATS = Path.of("nnue_v3_feature_stats.json");
  private static final String OPEN = "<script>const DATA = ";
  private static final String CLOSE = ";</script>";

  private static String html() throws Exception {
    assertTrue(Files.exists(HTML), "missing " + HTML);
    return Files.readString(HTML);
  }

  @Test
  public void testSelfContained() throws Exception {
    String h = html();
    assertFalse(h.contains("http://"), "page references an external http url");
    assertFalse(h.contains("https://"), "page references an external https url");
    assertFalse(h.contains("src="), "page pulls in an external asset via src=");
    assertFalse(h.contains("@import"), "page imports an external stylesheet");
    assertFalse(h.contains("<link"), "page links an external stylesheet/font");
  }

  @Test
  public void testInlinedDataMatchesStats() throws Exception {
    String h = html();
    int from = h.indexOf(OPEN);
    assertTrue(from >= 0, "no inlined DATA block");
    int to = h.indexOf(CLOSE, from);
    assertTrue(to > from, "unterminated DATA block");

    ObjectMapper om = new ObjectMapper();
    JsonNode inlined = om.readTree(h.substring(from + OPEN.length(), to));
    JsonNode stats = om.readTree(STATS.toFile());

    // Whole-tree, not headline numbers: the splice is a manual sed, so a stale or truncated middle
    // of the DATA block is exactly the failure mode worth catching.
    assertEquals(stats, inlined, "inlined DATA is not the committed " + STATS);
    assertEquals(stats.path("meta").path("feature_count").asInt(), inlined.path("features").size());
  }
}
