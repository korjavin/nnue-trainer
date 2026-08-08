package com.engine.nnue_trainer.mcts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.board.MoveAction;
import com.engine.nnue_trainer.board.Pos;
import com.engine.nnue_trainer.search.gobot.GoState;
import com.engine.nnue_trainer.train.GameRecorder;
import com.engine.nnue_trainer.train.GamesDbReplay;
import com.engine.nnue_trainer.v2.PatternContract;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Curriculum rows must be indistinguishable from {@link SelfPlayMcts} rows: same keys, same
 * mover-relative feature frame, same flat action space, and z in the ABSOLUTE frame (the v3
 * mover-flip lesson). Plus the human-name filter and per-(seed, shard) determinism.
 */
class HumanCurriculumEmitterTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final int FLAT = 144 + 144 * 144;

  /** A short valid game: P1 grows from (0,0), P2 grows from (11,11). Not finished — fine. */
  private static JsonNode turns() throws Exception {
    return MAPPER.readTree(
        "["
            + "{\"player\":1,\"moves\":[{\"type\":\"place\",\"row\":0,\"col\":1},"
            + "{\"type\":\"place\",\"row\":1,\"col\":1},{\"type\":\"place\",\"row\":1}]},"
            + "{\"player\":2,\"moves\":[{\"type\":\"place\",\"row\":11,\"col\":10},"
            + "{\"type\":\"place\",\"row\":10,\"col\":10},{\"type\":\"place\",\"row\":10,\"col\":11}]}"
            + "]");
  }

  @Test
  void rowsMatchTheSelfPlaySchemaAndZIsAbsolute() throws Exception {
    // The DB says P2 won (result=2) -> z = -1 in the ABSOLUTE frame on EVERY row, including
    // the mover==2 rows — the exact sign a mover-frame emitter would get wrong.
    List<ObjectNode> rows =
        HumanCurriculumEmitter.emitGame("g1", turns(), -1, 123L, 16, new MctsSearcher.Config());
    assertEquals(6, rows.size(), "3 P1 actions + 3 P2 actions, none forced");
    Set<Integer> movers = new HashSet<>();
    for (ObjectNode r : rows) {
      List<String> keys = new ArrayList<>();
      r.fieldNames().forEachRemaining(keys::add);
      assertEquals(
          List.of("g", "sym", "ml", "nuo", "nux", "mover", "pi", "pv", "z"),
          keys,
          "exact SelfPlayMcts key set and order");
      assertEquals(144, r.get("sym").size());
      int ml = r.get("ml").asInt();
      assertTrue(ml >= 1 && ml <= 3);
      int mover = r.get("mover").asInt();
      movers.add(mover);
      assertEquals(r.get("pi").size(), r.get("pv").size(), "pi/pv aligned");
      assertTrue(r.get("pi").size() > 1, "forced positions are not recorded");
      long visits = 0;
      for (int a = 0; a < r.get("pi").size(); a++) {
        int id = r.get("pi").get(a).asInt();
        assertTrue(id >= 0 && id < FLAT, "flat action id in range");
        visits += r.get("pv").get(a).asInt();
      }
      assertEquals(16, visits, "pv is the root visit distribution, summing to the sim budget");
      assertEquals(-1, r.get("z").asInt(), "z is absolute — NOT flipped on mover==2 rows");
    }
    assertTrue(movers.contains(1) && movers.contains(2), "both movers recorded");
  }

  /**
   * THE frame test: the mover==2 rows must encode the board in P2's frame — P2's own base at
   * (11,11) carries the same symbol P1's base at (0,0) carries on the mover==1 rows.
   */
  @Test
  void symIsMoverRelativeExactlyLikeSelfPlayMcts() throws Exception {
    List<ObjectNode> rows =
        HumanCurriculumEmitter.emitGame("g1", turns(), 1, 5L, 4, new MctsSearcher.Config());
    int ownBase1 = PatternContract.getSymbol(new Cell(1, CellKind.BASE), 1);
    int oppBase1 = PatternContract.getSymbol(new Cell(2, CellKind.BASE), 1);
    for (ObjectNode r : rows) {
      int atP1Base = r.get("sym").get(0).asInt(); // cell (0,0)
      int atP2Base = r.get("sym").get(143).asInt(); // cell (11,11)
      if (r.get("mover").asInt() == 1) {
        assertEquals(ownBase1, atP1Base, "mover 1: own base at (0,0)");
        assertEquals(oppBase1, atP2Base, "mover 1: opponent base at (11,11)");
      } else {
        assertEquals(ownBase1, atP2Base, "mover 2: OWN base symbol at (11,11) — P2's frame");
        assertEquals(oppBase1, atP1Base, "mover 2: opponent base at (0,0) — P2's frame");
      }
    }
  }

  /** The first emitted row is byte-identical to SelfPlayMcts.row on the same searched root. */
  @Test
  void firstRowEqualsSelfPlayMctsRowOnTheSamePosition() throws Exception {
    long gameSeed = 99L;
    MctsSearcher.Config template = new MctsSearcher.Config();
    List<ObjectNode> rows =
        HumanCurriculumEmitter.emitGame("g1", turns(), 1, gameSeed, 16, template);

    GoState start =
        GoState.fromBoard(
            GamesDbReplay.initialBoard(12, 12), 1, GoState.ACTIONS_PER_TURN, new boolean[2]);
    MctsSearcher.Config cfg = SelfPlayMcts.copyWithSeed(template, SelfPlayMcts.mix64(gameSeed ^ 1));
    MctsSearcher s = new MctsSearcher(start, cfg);
    s.runSims(16);
    ObjectNode expected = SelfPlayMcts.row("g1", start, s.root());
    expected.put("z", 1);
    assertEquals(expected, rows.get(0), "identical position + seed => identical row");
  }

  @Test
  void deterministicPerSeed() throws Exception {
    String a =
        HumanCurriculumEmitter.emitGame("g1", turns(), -1, 7L, 8, new MctsSearcher.Config())
            .toString();
    String b =
        HumanCurriculumEmitter.emitGame("g1", turns(), -1, 7L, 8, new MctsSearcher.Config())
            .toString();
    assertEquals(a, b, "same seed => byte-identical rows (root noise is OFF here)");
  }

  @Test
  void humanNameFilterKeepsHumansAndDropsAllBotFamilies() {
    assertTrue(HumanCurriculumEmitter.isHuman("DarkOtter76"));
    assertTrue(HumanCurriculumEmitter.isHuman("AncientDolphin78"));
    assertTrue(HumanCurriculumEmitter.isHuman("Unknown"), "conservative: unrecognized stays human");
    assertTrue(HumanCurriculumEmitter.isHuman("Botanist"), "'Bot ' needs the space");
    assertFalse(HumanCurriculumEmitter.isHuman("NNUE Bot 8341"));
    assertFalse(HumanCurriculumEmitter.isHuman("nNuE Bot 5054"));
    assertFalse(HumanCurriculumEmitter.isHuman("nnue Bot 2034"), "case-insensitive");
    assertFalse(HumanCurriculumEmitter.isHuman("Bot 1037"));
    assertFalse(HumanCurriculumEmitter.isHuman("GoBot v2"));
    assertFalse(HumanCurriculumEmitter.isHuman(null));
    assertFalse(HumanCurriculumEmitter.isHuman(""));
  }

  @Test
  void mainFiltersHumanGamesAndShardsDeterministically(@TempDir Path tmp) throws Exception {
    Path db = tmp.resolve("games.db");
    List<GameRecorder.Turn> game = new ArrayList<>();
    GameRecorder.Turn t1 = new GameRecorder.Turn(1);
    t1.moves.add(new MoveAction(new Pos(0, 1)));
    t1.moves.add(new MoveAction(new Pos(1, 1)));
    t1.moves.add(new MoveAction(new Pos(1, 0)));
    GameRecorder.Turn t2 = new GameRecorder.Turn(2);
    t2.moves.add(new MoveAction(new Pos(11, 10)));
    t2.moves.add(new MoveAction(new Pos(10, 10)));
    t2.moves.add(new MoveAction(new Pos(10, 11)));
    game.add(t1);
    game.add(t2);
    try (GameRecorder rec = new GameRecorder(db.toString())) {
      rec.record("DarkOtter76", "NNUE Bot 8341", 2, "no_moves", GameRecorder.now(), game);
      rec.record("Bot 1037", "GoBot v2", 1, "no_moves", GameRecorder.now(), game);
    }

    Path humans = tmp.resolve("humans.jsonl");
    HumanCurriculumEmitter.main(
        new String[] {db.toString(), humans.toString(), "4", "--human-only"});
    List<String> humanLines = Files.readAllLines(humans);
    assertEquals(6, humanLines.size(), "only the human-vs-bot game is emitted");
    for (String line : humanLines) {
      assertEquals(-1, MAPPER.readTree(line).get("z").asInt(), "human game: P2 won -> z=-1");
    }

    Path all = tmp.resolve("all.jsonl");
    HumanCurriculumEmitter.main(new String[] {db.toString(), all.toString(), "4"});
    assertEquals(12, Files.readAllLines(all).size(), "without --human-only both games emit");

    // Two shards partition the unsharded output exactly (deterministic per (seed, shard)).
    Path s0 = tmp.resolve("s0.jsonl");
    Path s1 = tmp.resolve("s1.jsonl");
    HumanCurriculumEmitter.main(new String[] {db.toString(), s0.toString(), "4", "0", "2", "11"});
    HumanCurriculumEmitter.main(new String[] {db.toString(), s1.toString(), "4", "1", "2", "11"});
    List<String> merged = new ArrayList<>(Files.readAllLines(s0));
    merged.addAll(Files.readAllLines(s1));
    assertEquals(new HashSet<>(Files.readAllLines(all)), new HashSet<>(merged));
  }
}
