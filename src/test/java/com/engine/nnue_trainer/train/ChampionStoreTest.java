package com.engine.nnue_trainer.train;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.engine.nnue_trainer.train.GauntletMatch.Result;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Task 2: promotion decision + champion store (atomic replace + history append). */
class ChampionStoreTest {

  private static Result r(int w, int l, int d) {
    return new Result(w, l, d);
  }

  @Test
  void promotesOnlyWhenBeatsChampionAndHoldsTheBar() {
    int margin = 2;
    Result championVsBar = r(0, 6, 0); // champion loses 0-6 to the hand-tuned bar

    // Beats champion by exactly the margin AND holds the bar as well as the champion → promote.
    assertTrue(
        ChampionStore.shouldPromote(r(3, 1, 0), r(0, 6, 0), championVsBar, margin),
        "clear win + no worse vs bar must promote");

    // Beats champion but is WORSE against the bar than the champion → reject (never regress).
    assertFalse(
        ChampionStore.shouldPromote(r(3, 1, 0), r(0, 8, 0), championVsBar, margin),
        "worse vs the hand-tuned bar must not promote");

    // Does not beat champion by the margin → reject.
    assertFalse(
        ChampionStore.shouldPromote(r(1, 0, 3), r(2, 4, 0), championVsBar, margin),
        "margin below threshold must not promote");
  }

  @Test
  void promoteReplacesChampionAtomicallyAndAppendsHistory(@TempDir Path dir) throws IOException {
    Path champion = dir.resolve("nnue_weights.json");
    Path history = dir.resolve("champions/history.log");
    Path challenger = dir.resolve("challenger.json");
    Files.writeString(champion, "OLD");
    Files.writeString(challenger, "NEW");

    ChampionStore store = new ChampionStore(champion, history);
    store.promote(1, challenger, r(4, 1, 0), r(0, 5, 0));

    assertEquals("NEW", Files.readString(champion), "champion weights replaced with challenger");
    List<String> lines = Files.readAllLines(history);
    assertEquals(1, lines.size());
    assertTrue(lines.get(0).contains("gen=1"));
    assertTrue(lines.get(0).contains("promoted=true"));
    assertTrue(lines.get(0).contains("margin=+3"), "vs-champion margin logged: " + lines.get(0));
  }

  @Test
  void rejectLeavesChampionUntouchedAndLogs(@TempDir Path dir) throws IOException {
    Path champion = dir.resolve("nnue_weights.json");
    Path history = dir.resolve("champions/history.log");
    Files.writeString(champion, "OLD");

    ChampionStore store = new ChampionStore(champion, history);
    store.reject(2, r(1, 3, 0), r(0, 6, 0));

    assertEquals("OLD", Files.readString(champion), "rejected challenger must not touch champion");
    List<String> lines = Files.readAllLines(history);
    assertEquals(1, lines.size());
    assertTrue(lines.get(0).contains("promoted=false"));
    assertTrue(lines.get(0).contains("margin=-2"), "vs-champion margin logged: " + lines.get(0));
  }
}
