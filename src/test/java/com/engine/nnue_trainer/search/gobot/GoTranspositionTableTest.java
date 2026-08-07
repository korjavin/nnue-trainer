package com.engine.nnue_trainer.search.gobot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.engine.nnue_trainer.board.Action;
import com.engine.nnue_trainer.board.MoveAction;
import com.engine.nnue_trainer.board.PlaceNeutralsAction;
import com.engine.nnue_trainer.board.Pos;
import org.junit.jupiter.api.Test;

/** Stage-2 self-checks: packing round-trips, mate-score ply rebasing, replacement policy. */
public class GoTranspositionTableTest {

  @Test
  public void packedEntryRoundTrips() {
    GoTranspositionTable tt = new GoTranspositionTable(8);
    tt.bumpGeneration();
    int action = GoTranspositionTable.encodeAction(new MoveAction(new Pos(3, 7)), 12, 144);
    tt.store(0xDEADBEEFL, 9, GoBotSearcher.FLAG_LOWER, -12345, action);

    long e = tt.probe(0xDEADBEEFL);
    assertNotEquals(0L, e);
    assertEquals(9, GoTranspositionTable.depthOf(e));
    assertEquals(GoBotSearcher.FLAG_LOWER, GoTranspositionTable.flagOf(e));
    assertEquals(-12345, GoTranspositionTable.scoreOf(e));
    assertEquals(
        new MoveAction(new Pos(3, 7)),
        GoTranspositionTable.decodeAction(GoTranspositionTable.actionBitsOf(e), 12));
    assertEquals(0L, tt.probe(0xDEADBEEFL ^ 1L), "different key misses");
  }

  @Test
  public void neutralPairEncodingNormalizesOrder() {
    Pos a = new Pos(1, 2);
    Pos b = new Pos(4, 5);
    int ab = GoTranspositionTable.encodeAction(new PlaceNeutralsAction(a, b), 12, 144);
    int ba = GoTranspositionTable.encodeAction(new PlaceNeutralsAction(b, a), 12, 144);
    assertEquals(ab, ba, "unordered pair must encode identically");
    Action decoded = GoTranspositionTable.decodeAction(ab, 12);
    assertEquals(new PlaceNeutralsAction(a, b), decoded);
  }

  @Test
  public void unencodableActionsStoreAsAbsent() {
    // 20x20 board: neutral-pair indices above 255 cannot be packed into two bytes.
    int bits =
        GoTranspositionTable.encodeAction(
            new PlaceNeutralsAction(new Pos(19, 19), new Pos(0, 0)), 20, 400);
    assertEquals(0, bits);
    assertNull(GoTranspositionTable.decodeAction(0, 20));
  }

  @Test
  public void mateScoresRebaseAcrossPlies() {
    // Root-relative mate found at ply 2 with 5 plies to go: MATE_SCORE - 7.
    long rootRelative = GoBotSearcher.MATE_SCORE - 7;
    int stored = GoTranspositionTable.toStoredScore(rootRelative, 2); // node-relative: MATE - 5
    assertEquals(GoBotSearcher.MATE_SCORE - 5, stored);
    // Probing the same position from ply 4: mate is now 4 + 5 = 9 plies from root.
    assertEquals(GoBotSearcher.MATE_SCORE - 9, GoTranspositionTable.fromStoredScore(stored, 4));
    // Shorter mates stay preferred: a mate 3 plies from the node beats one 5 plies away at any ply.
    int shorter = GoTranspositionTable.toStoredScore(GoBotSearcher.MATE_SCORE - 5, 2);
    assertTrue(
        GoTranspositionTable.fromStoredScore(shorter, 4)
            > GoTranspositionTable.fromStoredScore(stored, 4));
    // Negative (mated) side mirrors.
    long mated = -GoBotSearcher.MATE_SCORE + 7;
    int storedMated = GoTranspositionTable.toStoredScore(mated, 2);
    assertEquals(-GoBotSearcher.MATE_SCORE + 5, storedMated);
    assertEquals(
        -GoBotSearcher.MATE_SCORE + 9, GoTranspositionTable.fromStoredScore(storedMated, 4));
    // Ordinary scores pass through untouched.
    assertEquals(4321, GoTranspositionTable.toStoredScore(4321, 7));
    assertEquals(4321, GoTranspositionTable.fromStoredScore(4321, 7));
  }

  @Test
  public void replacementIsDepthPreferredWithGenerationAging() {
    GoTranspositionTable tt = new GoTranspositionTable(4); // 16 slots → easy same-slot collisions
    tt.bumpGeneration();
    long keyA = 0x10L; // slot 0
    long keyB = 0x20L; // slot 0 too
    tt.store(keyA, 8, GoBotSearcher.FLAG_EXACT, 1, 0);
    // Same generation, different key, shallower: must NOT clobber the deep entry.
    tt.store(keyB, 3, GoBotSearcher.FLAG_EXACT, 2, 0);
    assertNotEquals(0L, tt.probe(keyA), "deep same-generation entry survives");
    assertEquals(0L, tt.probe(keyB));
    // Same key: always replaced, even shallower (fresh bounds beat stale depth).
    tt.store(keyA, 2, GoBotSearcher.FLAG_UPPER, 3, 0);
    assertEquals(2, GoTranspositionTable.depthOf(tt.probe(keyA)));
    // Next generation: anything old is replaceable regardless of depth.
    tt.store(keyA, 9, GoBotSearcher.FLAG_EXACT, 4, 0);
    tt.bumpGeneration();
    tt.store(keyB, 1, GoBotSearcher.FLAG_EXACT, 5, 0);
    assertNotEquals(0L, tt.probe(keyB), "older generation loses the slot");
    assertEquals(0L, tt.probe(keyA));
  }
}
