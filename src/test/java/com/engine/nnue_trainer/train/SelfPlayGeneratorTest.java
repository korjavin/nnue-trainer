package com.engine.nnue_trainer.train;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.nnue.NNUEModel;
import com.engine.nnue_trainer.search.SearchEngine;
import com.engine.nnue_trainer.search.gobot.GoState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;

class SelfPlayGeneratorTest {

  private static Board startingBoard() {
    Board board = new Board(12, 12);
    board.setCell(0, 0, new Cell(1, CellKind.BASE));
    board.setCell(11, 11, new Cell(2, CellKind.BASE));
    return board;
  }

  private static SelfPlayGenerator.Config tdConfig(double lambda) {
    SelfPlayGenerator.Config config = new SelfPlayGenerator.Config();
    config.labelMode = SelfPlayGenerator.LabelMode.TD_LEAF;
    config.tdLambda = lambda;
    config.searchDepth = 2;
    return config;
  }

  @Test
  void tdLeafTargetEqualsSearchValueAtLambdaZero() {
    Board board = startingBoard();
    // Two fresh engines with the same warm-start weights are deterministic, so the standalone
    // search value must equal the TD-leaf(λ=0) target computed inside the generator.
    float searchValue =
        new SearchEngine(NNUEModel.createDefault())
            .findBestActionUsingModel(board, 1, 2, false)
            .score;
    float target =
        SelfPlayGenerator.computeTarget(
            new SearchEngine(NNUEModel.createDefault()), board, 1, false, 0, tdConfig(0.0));
    assertEquals(searchValue, target, 1e-4, "λ=0 target should equal the raw search value");
  }

  @Test
  void tdLeafTargetEqualsOutcomeAtLambdaOne() {
    Board board = startingBoard();
    SearchEngine engine = new SearchEngine(NNUEModel.createDefault());
    // Side to move (player 1) won → +1, lost → -1, regardless of the search value.
    assertEquals(
        1.0f, SelfPlayGenerator.computeTarget(engine, board, 1, false, 1, tdConfig(1.0)), 1e-6);
    assertEquals(
        -1.0f, SelfPlayGenerator.computeTarget(engine, board, 1, false, 2, tdConfig(1.0)), 1e-6);
  }

  @Test
  void tdLeafTargetHasSideToMoveSign() {
    Board board = startingBoard();
    SearchEngine engine = new SearchEngine(NNUEModel.createDefault());
    // Same winner (player 1), opposite side to move → opposite sign.
    float fromWinner = SelfPlayGenerator.computeTarget(engine, board, 1, false, 1, tdConfig(1.0));
    float fromLoser = SelfPlayGenerator.computeTarget(engine, board, 2, false, 1, tdConfig(1.0));
    assertEquals(1.0f, fromWinner, 1e-6);
    assertEquals(-1.0f, fromLoser, 1e-6);
  }

  @Test
  void testSingleGameGeneration() throws Exception {
    File tempDir = new File(System.getProperty("java.io.tmpdir"));
    File tempFile =
        new File(tempDir, "self_play_data_test_" + System.currentTimeMillis() + ".json");
    if (tempFile.exists()) {
      tempFile.delete();
    }

    SelfPlayGenerator.main(new String[] {"1", tempFile.getAbsolutePath()});

    assertTrue(tempFile.exists(), "Dataset JSON file should have been generated.");
    assertTrue(tempFile.length() > 0, "Dataset JSON file should not be empty.");

    // Clean up
    tempFile.delete();
  }

  @Test
  void testVariableBoardSizeGeneration() {
    // A 7x7 board (not the historical 12x12) plays through generate() with no 12x12 assumption.
    // The v1 864-dim one-hot mapper is 12x12-only, so the v1 dataset is empty off-12x12 by design
    // (the raw corpus path carries non-12x12 data); the point here is that generation runs clean.
    SelfPlayGenerator.Config config = new SelfPlayGenerator.Config();
    config.rows = 7;
    config.cols = 7;
    config.numGames = 1;
    config.maxTurns = 15;
    config.seed = 42;

    SelfPlayGenerator.GenerationResult result = SelfPlayGenerator.generate(config, null);

    assertTrue(result != null && result.dataset != null, "7x7 generation should complete.");
  }

  @Test
  void testRawEmitProducesValidJsonl() throws Exception {
    // Raw corpus on a non-12x12 board: each JSONL line must parse and satisfy the v2 schema.
    File tempDir = new File(System.getProperty("java.io.tmpdir"));
    File rawFile = new File(tempDir, "raw_corpus_test_" + System.currentTimeMillis() + ".jsonl");
    rawFile.delete();

    SelfPlayGenerator.Config config = new SelfPlayGenerator.Config();
    config.rows = 7;
    config.cols = 7;
    config.numGames = 2;
    config.maxTurns = 15;
    config.seed = 42;
    config.rawOutPath = rawFile.getAbsolutePath();

    SelfPlayGenerator.GenerationResult result = SelfPlayGenerator.generate(config, null);
    assertTrue(
        result.rawPositions != null && !result.rawPositions.isEmpty(),
        "Raw positions should be collected.");

    // Persist via the same compact JSONL writer main() uses, then validate line-by-line.
    ObjectMapper mapper = new ObjectMapper();
    try (java.io.BufferedWriter w = Files.newBufferedWriter(rawFile.toPath())) {
      for (SelfPlayGenerator.RawPosition p : result.rawPositions) {
        w.write(mapper.writeValueAsString(p));
        w.newLine();
      }
    }

    List<String> lines = Files.readAllLines(rawFile.toPath());
    assertTrue(lines.size() == result.rawPositions.size(), "One line per position.");
    for (String line : lines) {
      JsonNode node = mapper.readTree(line);
      int rows = node.get("rows").asInt();
      int cols = node.get("cols").asInt();
      assertEquals(7, rows, "rows");
      assertEquals(7, cols, "cols");
      assertTrue(node.has("stm"), "has stm");
      double wdl = node.get("wdl").asDouble();
      assertTrue(wdl == 0.0 || wdl == 0.5 || wdl == 1.0, "wdl in {0,0.5,1}, was " + wdl);

      JsonNode cells = node.get("cells");
      assertEquals(rows, cells.size(), "cells outer dim == rows");
      for (JsonNode row : cells) {
        assertEquals(cols, row.size(), "cells inner dim == cols");
        for (JsonNode cell : row) {
          String kind = cell.get("kind").asText();
          int owner = cell.get("owner").asInt();
          boolean noOwner = kind.equals("EMPTY") || kind.equals("NEUTRAL");
          assertEquals(noOwner, owner == -1, "owner==-1 iff EMPTY/NEUTRAL for kind " + kind);
        }
      }
    }
    rawFile.delete();
  }

  @Test
  void negamaxPathHonorsDedup() {
    // config.dedup was only honored on the GoBot path: the default NEGAMAX path added every record
    // and used the hash for distinctGameRatio only, so a deterministic run exported the same game
    // numGames times.
    // Every game opens on the same start board with the same side to move, so numGames >= 2
    // guarantees at least one exact-duplicate position. Assertions stay WITHIN a run: the engine's
    // transposition table persists across games, so two runs are not comparable.
    SelfPlayGenerator.Config on = new SelfPlayGenerator.Config();
    on.numGames = 2;
    on.maxTurns = 4;
    on.searchDepth = 1;
    on.rawOutPath = "unused-but-enables-raw-collection.jsonl";
    SelfPlayGenerator.GenerationResult deduped = SelfPlayGenerator.generate(on, null);

    assertTrue(
        deduped.dataset.size() < deduped.totalPositionsSeen,
        "dedup must drop the repeated start position (dataset "
            + deduped.dataset.size()
            + " vs seen "
            + deduped.totalPositionsSeen
            + ")");
    java.util.Set<String> seen = new java.util.HashSet<>();
    for (SelfPlayGenerator.TrainingRecord rec : deduped.dataset) {
      assertTrue(seen.add(java.util.Arrays.toString(rec.features)), "no duplicates after dedup");
    }
    // The raw JSONL export is the path gen_v2_corpus.sh uses, so it must honor the flag too.
    assertTrue(
        deduped.rawPositions.size() < deduped.totalPositionsSeen,
        "raw corpus export must honor dedup as well");

    SelfPlayGenerator.Config off = new SelfPlayGenerator.Config();
    off.numGames = on.numGames;
    off.maxTurns = on.maxTurns;
    off.searchDepth = 1;
    off.rawOutPath = on.rawOutPath;
    off.dedup = false;
    SelfPlayGenerator.GenerationResult raw = SelfPlayGenerator.generate(off, null);
    assertEquals(
        raw.totalPositionsSeen, raw.dataset.size(), "dedup=false keeps every position seen");
    assertEquals(
        raw.totalPositionsSeen, raw.rawPositions.size(), "dedup=false keeps every raw position");
  }

  @Test
  void selfPlayTransitionsFollowTheCanonicalRules() {
    // The negamax loop applied moves with SearchEngine.applyAction, which erases cells that lose
    // base-connectivity — the real rules keep them (and FORTIFY a captured cell). Erasure is
    // observable as a cell going back to EMPTY, which canonical play can never do.
    SelfPlayGenerator.Config config = new SelfPlayGenerator.Config();
    config.rows = 7;
    config.cols = 7;
    config.numGames = 1; // ONE game: consecutive raw positions are then consecutive plies
    config.maxTurns = 40;
    config.seed = 3;
    config.epsilon = 1.0; // random play: reaches captures and cut-off chains
    config.exploreTurns = 1000;
    config.dedup = false; // keep every snapshot: the invariant is per consecutive pair
    config.rawOutPath = "unused-but-enables-raw-collection.jsonl";

    SelfPlayGenerator.GenerationResult result = SelfPlayGenerator.generate(config, null);

    boolean sawFortified = false;
    SelfPlayGenerator.RawPosition prev = null;
    for (SelfPlayGenerator.RawPosition p : result.rawPositions) {
      for (int r = 0; r < p.rows; r++) {
        for (int c = 0; c < p.cols; c++) {
          sawFortified |= "FORTIFIED".equals(p.cells[r][c].kind);
          if (prev != null && "EMPTY".equals(p.cells[r][c].kind)) {
            assertEquals(
                "EMPTY",
                prev.cells[r][c].kind,
                "cell (" + r + "," + c + ") was emptied — the rules never erase an occupied cell");
          }
        }
      }
      prev = p;
    }
    assertTrue(sawFortified, "captures must FORTIFY, so FORTIFIED cells must appear in the corpus");
  }

  @Test
  void generatedPositionsRespectThePerGameNeutralBudget() {
    // One PlaceNeutralsAction per player per GAME (GoState.legalActions / GameLoopHandler), and it
    // converts exactly two own cells → at most 4 NEUTRAL cells can ever exist. The generator used
    // to re-arm the budget every turn, emitting boards with up to 18 neutrals into the raw corpus.
    SelfPlayGenerator.Config config = new SelfPlayGenerator.Config();
    config.rows = 7;
    config.cols = 7;
    config.numGames = 3;
    config.maxTurns = 40;
    config.seed = 42;
    config.epsilon = 1.0; // pure random play: actually exercises the neutral actions
    config.exploreTurns = 1000;
    config.rawOutPath = "unused-but-enables-raw-collection.jsonl";

    SelfPlayGenerator.GenerationResult result = SelfPlayGenerator.generate(config, null);

    int maxNeutrals = 0;
    for (SelfPlayGenerator.RawPosition p : result.rawPositions) {
      int neutrals = 0;
      for (SelfPlayGenerator.RawCell[] row : p.cells) {
        for (SelfPlayGenerator.RawCell cell : row) {
          if ("NEUTRAL".equals(cell.kind)) {
            neutrals++;
          }
        }
      }
      maxNeutrals = Math.max(maxNeutrals, neutrals);
    }
    assertTrue(maxNeutrals <= 4, "at most 2 neutral pairs per game, saw " + maxNeutrals);
  }

  @Test
  void territoryFilledPositionIsLabeledDecisiveNotDraw() {
    // A full both-bases-alive board (nobody can move) with p1 owning the majority: the generator's
    // canonical winner (fromBoard().outcomeWinner(), same call canonicalWinner makes) must pick p1,
    // and OUTCOME labeling must then map it to a decisive ±1 — not the old base-survival draw.
    Board b = new Board(5, 5);
    b.setCell(0, 0, new Cell(1, CellKind.BASE));
    b.setCell(4, 4, new Cell(2, CellKind.BASE));
    for (int r = 0; r < 5; r++) {
      for (int c = 0; c < 5; c++) {
        if ((r == 0 && c == 0) || (r == 4 && c == 4)) {
          continue;
        }
        b.setCell(r, c, new Cell(r <= 2 ? 1 : 2, CellKind.FORTIFIED)); // p1 territory majority
      }
    }
    int winner = GoState.fromBoard(b, 1, GoState.ACTIONS_PER_TURN, new boolean[2]).outcomeWinner();
    assertEquals(1, winner, "both bases alive but p1 has more territory → p1 wins, not a draw");

    SearchEngine engine = new SearchEngine();
    SelfPlayGenerator.Config outcome = new SelfPlayGenerator.Config();
    assertEquals(
        1.0f,
        SelfPlayGenerator.computeTarget(engine, b, 1, false, winner, outcome),
        1e-6,
        "territory winner labels the side-to-move position decisively (wdl 1.0, not 0.5)");
  }

  @Test
  void generatedCorpusIsNotAllDraws() {
    // The bug: every non-12x12 / turn-capped game defaulted to draw (wdl 0.5). With canonical
    // territory labeling, decisive results must appear across a few seeded games on a small board.
    SelfPlayGenerator.Config config = new SelfPlayGenerator.Config();
    config.rows = 5;
    config.cols = 5;
    config.numGames = 4;
    config.maxTurns = 30;
    config.seed = 7;
    config.rawOutPath = "unused"; // enables raw collection; nothing is written from generate()

    SelfPlayGenerator.GenerationResult result = SelfPlayGenerator.generate(config, null);
    assertTrue(
        result.rawPositions.stream().anyMatch(p -> p.wdl != 0.5),
        "at least one position should carry a decisive territory label, not all-draws");
  }

  @Test
  void testDiverseDatasetGeneration() {
    SelfPlayGenerator.Config config = new SelfPlayGenerator.Config();
    config.numGames = 1;
    config.maxTurns = 15;
    config.epsilon = 1.0;
    config.exploreTurns = 15;
    config.seed = 42; // deterministic: an unseeded Random here flakes below the 0.8 threshold

    SelfPlayGenerator.GenerationResult result = SelfPlayGenerator.generate(config, null);

    assertTrue(result.dataset.size() > 0, "Dataset should not be empty.");
    assertTrue(
        result.distinctGameRatio > 0.8,
        "Distinct game ratio should be greater than 0.8, was: " + result.distinctGameRatio);
  }
}
