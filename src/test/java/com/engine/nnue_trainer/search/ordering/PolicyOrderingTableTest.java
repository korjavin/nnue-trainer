package com.engine.nnue_trainer.search.ordering;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A bad weights file must fail at {@link PolicyOrderingTable#load}, once — never mid-search. */
class PolicyOrderingTableTest {

  @TempDir Path tmp;

  private Path write(String json) throws IOException {
    Path p = tmp.resolve("w.json");
    Files.writeString(p, json);
    return p;
  }

  /** Full-shape artifact with one substitution at {@code w[0][0]}. */
  private static String fullMatrix(String w00) {
    StringBuilder sb = new StringBuilder("{\"meta\":{\"features\":1156,\"cells\":144},\"w\":[");
    for (int f = 0; f < PolicyOrderingTable.FEATURES; f++) {
      if (f > 0) {
        sb.append(',');
      }
      sb.append('[');
      for (int c = 0; c < PolicyOrderingTable.CELLS; c++) {
        if (c > 0) {
          sb.append(',');
        }
        sb.append(f == 0 && c == 0 ? w00 : "0");
      }
      sb.append(']');
    }
    return sb.append("]}").toString();
  }

  @Test
  void missingMetaFails() throws IOException {
    Path p = write("{\"w\":[]}");
    IOException e = assertThrows(IOException.class, () -> PolicyOrderingTable.load(p));
    assertTrue(e.getMessage().contains("meta"));
  }

  @Test
  void wrongFeatureCountFails() throws IOException {
    Path p = write("{\"meta\":{\"features\":1152,\"cells\":144},\"w\":[]}");
    IOException e = assertThrows(IOException.class, () -> PolicyOrderingTable.load(p));
    assertTrue(e.getMessage().contains("1156"));
  }

  @Test
  void wrongRowCountFails() throws IOException {
    Path p = write("{\"meta\":{\"features\":1156,\"cells\":144},\"w\":[[1],[2]]}");
    IOException e = assertThrows(IOException.class, () -> PolicyOrderingTable.load(p));
    assertTrue(e.getMessage().contains("1156 rows"));
  }

  @Test
  void shortRowFails() throws IOException {
    // 1156 rows present, but row 0 is too short.
    StringBuilder sb = new StringBuilder("{\"meta\":{\"features\":1156,\"cells\":144},\"w\":[");
    for (int f = 0; f < PolicyOrderingTable.FEATURES; f++) {
      sb.append(f == 0 ? "[1,2,3]" : ",[]");
    }
    Path p = write(sb.append("]}").toString());
    IOException e = assertThrows(IOException.class, () -> PolicyOrderingTable.load(p));
    assertTrue(e.getMessage().contains("144 entries"));
  }

  @Test
  void nonFiniteEntryFails() throws IOException {
    Path p = write(fullMatrix("\"nan\""));
    IOException e = assertThrows(IOException.class, () -> PolicyOrderingTable.load(p));
    assertTrue(e.getMessage().contains("finite"));
  }

  @Test
  void validFullMatrixLoads() throws Exception {
    PolicyOrderingTable t = PolicyOrderingTable.load(write(fullMatrix("0.5")));
    double[] s = t.score(new com.engine.nnue_trainer.board.Board(12, 12), 1, 3);
    // Empty board activates feature (0,0,EMPTY) = id 0, whose w00=0.5 lands on cell 0 only.
    org.junit.jupiter.api.Assertions.assertEquals(0.5, s[0], 1e-12);
    org.junit.jupiter.api.Assertions.assertEquals(0.0, s[1], 1e-12);
  }

  @Test
  void movesLeftOutOfRangeThrows() throws Exception {
    PolicyOrderingTable t = PolicyOrderingTable.load(write(fullMatrix("0")));
    assertThrows(
        IllegalArgumentException.class,
        () -> t.score(new com.engine.nnue_trainer.board.Board(12, 12), 1, 4));
  }
}
