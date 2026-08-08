package com.engine.nnue_trainer.mcts;

import com.engine.nnue_trainer.board.Action;
import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.board.MoveAction;
import com.engine.nnue_trainer.board.PlaceNeutralsAction;
import com.engine.nnue_trainer.board.Pos;
import com.engine.nnue_trainer.search.gobot.GoState;
import com.engine.nnue_trainer.v2.PatternContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Phase 2 self-play generator (plan {@code docs/plans/20260807-mcts-az-feasibility.md}): plays
 * MCTS-vs-MCTS games with the current policy(+value) artifact and records AlphaZero training rows.
 *
 * <p>Per plan: Dirichlet root noise ON, temperature τ=1 (sample ∝ root visits) for the first {@link
 * #TEMPERATURE_PLIES} plies then argmax; one JSONL row per multi-choice position.
 *
 * <p>Row schema: {@code {"g":gameId,"sym":[144 ints, mover-relative],"ml":1..3,"nuo":0|1,
 * "nux":0|1,"mover":1|2,"pi":[flat action ids],"pv":[root visit counts],"z":-1|0|1}}. Flat ids
 * match {@code train_policy.py}'s space: {@code cell} for moves, {@code 144 + i*144 + j} (i&lt;j)
 * for neutral pairs. {@code pi} covers <b>every</b> legal action (the mask), {@code pv} is the
 * policy target. {@code z} is the game outcome in the <b>absolute</b> frame (+1 = player 1 won,
 * from {@code outcomeWinner()}); the trainer flips it into each row's mover frame via {@code
 * "mover"} — the v3 mover-flip lesson, pinned by {@code SelfPlayMctsTest}.
 *
 * <p>CLI: {@code SelfPlayMcts <out.jsonl> <games> <sims> <shardIdx> <shardCount> <seed>}. {@code
 * games} is the global count; a shard plays game g iff {@code g % shardCount == shardIdx} (the
 * {@code V3DeepLabelEmitter} pattern), and every game's randomness derives from {@code (seed,
 * gameIdx)} only — output is deterministic per (seed, shard) regardless of shard count. Env knobs:
 * {@code MCTS_PRIOR} (artifact, default {@code mcts_policy.json}), {@code MCTS_VALUE=net} (use the
 * artifact's value head when present), {@code MCTS_CPUCT}, {@code MCTS_VALUE_SCALE}.
 */
public final class SelfPlayMcts {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final int BOARD = 12;
  private static final int CELLS = BOARD * BOARD;

  /** τ=1 window: 21 plies = 7 turns (plan Phase 0 task 1), then greedy argmax. */
  static final int TEMPERATURE_PLIES = 21;

  private static final int MAX_PLIES = 100 * GoState.ACTIONS_PER_TURN;

  private SelfPlayMcts() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 6) {
      System.err.println(
          "usage: SelfPlayMcts <out.jsonl> <games> <sims> <shardIdx> <shardCount> <seed>");
      System.exit(2);
    }
    Path out = Path.of(args[0]);
    int games = Integer.parseInt(args[1]);
    int sims = Integer.parseInt(args[2]);
    int shardIdx = Integer.parseInt(args[3]);
    int shardCount = Integer.parseInt(args[4]);
    long seed = Long.parseLong(args[5]);

    String priorPath = env("MCTS_PRIOR", "mcts_policy.json");
    MctsSearcher.Config template = envTemplate();
    boolean useValueNet = template.valueNet != null;
    template.rootNoise = true; // self-play exploration — the whole point of this mode

    long t0 = System.currentTimeMillis();
    long rows = 0;
    int played = 0;
    try (BufferedWriter w = Files.newBufferedWriter(out)) {
      for (int g = 0; g < games; g++) {
        if (g % shardCount != shardIdx) {
          continue;
        }
        rows +=
            playGame(
                "sp" + seed + "-" + g,
                mix64(seed ^ (0x9E3779B97F4A7C15L * (g + 1))),
                sims,
                template,
                w);
        played++;
      }
    }
    double secs = (System.currentTimeMillis() - t0) / 1000.0;
    System.out.printf(
        "shard %d/%d: games %d, rows %d, sims %d, prior %s, value %s, %.1fs (%.1f s/game)%n",
        shardIdx,
        shardCount,
        played,
        rows,
        sims,
        priorPath,
        useValueNet ? "net" : "hand-tuned",
        secs,
        played == 0 ? 0 : secs / played);
  }

  /** One self-play game from the production start; returns the number of rows written. */
  static long playGame(
      String gameId, long gameSeed, int sims, MctsSearcher.Config template, BufferedWriter w)
      throws Exception {
    return playGame(gameId, gameSeed, sims, template, w, freshState());
  }

  /** Test seam: same game loop from an arbitrary start state. */
  static long playGame(
      String gameId,
      long gameSeed,
      int sims,
      MctsSearcher.Config template,
      BufferedWriter w,
      GoState start)
      throws Exception {
    Random sampler = new Random(mix64(gameSeed ^ 0x5DEECE66DL));
    GoState state = start;
    List<ObjectNode> pending = new ArrayList<>();
    for (int ply = 0; ply < MAX_PLIES && !state.gameOver(); ply++) {
      MctsSearcher.Config cfg = copyWithSeed(template, mix64(gameSeed ^ (ply + 1)));
      MctsSearcher s = new MctsSearcher(state, cfg);
      s.runSims(sims);
      MctsSearcher.Node root = s.root();
      if (root.actions == null || root.actions.length == 0) {
        break; // terminal/stuck root — score what we have
      }
      if (root.actions.length > 1) {
        pending.add(row(gameId, state, root)); // forced positions carry no policy signal
      }
      int pick = ply < TEMPERATURE_PLIES ? sampleByVisits(root.n, sampler) : argmax(root.n);
      state = state.applyGenerated(root.actions[pick]);
    }
    // Absolute-frame outcome: +1/-1/0 straight from the single labeling rule (incl. territory
    // tie), shared with MCTS terminal backup — one flip point, in the trainer, via "mover".
    int z = (int) MctsSearcher.terminalValueAbs(state);
    for (ObjectNode n : pending) {
      n.put("z", z);
      w.write(MAPPER.writeValueAsString(n));
      w.newLine();
    }
    return pending.size();
  }

  /** One training row from a searched root (z is filled in when the game ends). */
  static ObjectNode row(String gameId, GoState state, MctsSearcher.Node root) {
    int mover = state.currentPlayer();
    ObjectNode n = MAPPER.createObjectNode();
    n.put("g", gameId);
    ArrayNode sym = n.putArray("sym");
    Board board = state.toBoard();
    for (int r = 0; r < BOARD; r++) {
      for (int c = 0; c < BOARD; c++) {
        sym.add(PatternContract.getSymbol(board.getCell(r, c), mover));
      }
    }
    n.put("ml", state.movesLeft());
    n.put("nuo", state.neutralUsed(mover) ? 1 : 0);
    n.put("nux", state.neutralUsed(3 - mover) ? 1 : 0);
    n.put("mover", mover);
    ArrayNode pi = n.putArray("pi");
    ArrayNode pv = n.putArray("pv");
    for (int a = 0; a < root.actions.length; a++) {
      pi.add(flatIndex(root.actions[a]));
      pv.add(root.n[a]);
    }
    return n;
  }

  /** Flat action id in the trainer's space: [0,144) moves, 144 + i*144 + j pairs (i&lt;j). */
  static int flatIndex(Action action) {
    if (action instanceof MoveAction) {
      return cell(((MoveAction) action).target);
    }
    PlaceNeutralsAction pn = (PlaceNeutralsAction) action;
    int i = cell(pn.pos1);
    int j = cell(pn.pos2);
    return CELLS + Math.min(i, j) * CELLS + Math.max(i, j);
  }

  private static int cell(Pos pos) {
    return pos.row * BOARD + pos.col;
  }

  static int sampleByVisits(int[] n, Random random) {
    long total = 0;
    for (int v : n) {
      total += v;
    }
    if (total <= 0) {
      return 0;
    }
    long r = (long) (random.nextDouble() * total);
    for (int a = 0; a < n.length; a++) {
      r -= n[a];
      if (r < 0) {
        return a;
      }
    }
    return n.length - 1;
  }

  private static int argmax(int[] n) {
    int best = 0;
    for (int a = 1; a < n.length; a++) {
      if (n[a] > n[best]) {
        best = a;
      }
    }
    return best;
  }

  /**
   * Search template from the shared env knobs ({@code MCTS_PRIOR}, {@code MCTS_VALUE=net}, {@code
   * MCTS_CPUCT}, {@code MCTS_VALUE_SCALE}) — one source of truth for every emitter that searches
   * with the current champion artifact. Root noise is left OFF; self-play turns it on itself.
   */
  static MctsSearcher.Config envTemplate() throws Exception {
    PolicyNetPrior prior = PolicyNetPrior.load(Path.of(env("MCTS_PRIOR", "mcts_policy.json")));
    MctsSearcher.Config t = new MctsSearcher.Config();
    t.cpuct = Double.parseDouble(env("MCTS_CPUCT", "1.5"));
    t.valueScale = Double.parseDouble(env("MCTS_VALUE_SCALE", "12000"));
    t.prior = prior;
    t.valueNet = "net".equals(env("MCTS_VALUE", "")) && prior.hasValueHead() ? prior : null;
    return t;
  }

  static MctsSearcher.Config copyWithSeed(MctsSearcher.Config t, long seed) {
    MctsSearcher.Config c = new MctsSearcher.Config();
    c.cpuct = t.cpuct;
    c.valueScale = t.valueScale;
    c.prior = t.prior;
    c.valueNet = t.valueNet;
    c.rootNoise = t.rootNoise;
    c.noiseAlpha = t.noiseAlpha;
    c.noiseEpsilon = t.noiseEpsilon;
    c.seed = seed;
    return c;
  }

  /** The production start position (mirrors {@code GauntletMatch.freshBoard}). */
  static GoState freshState() {
    Board board = new Board(BOARD, BOARD);
    board.setCell(0, 0, new Cell(1, CellKind.BASE));
    board.setCell(BOARD - 1, BOARD - 1, new Cell(2, CellKind.BASE));
    return GoState.fromBoard(board, 1, GoState.ACTIONS_PER_TURN, new boolean[2]);
  }

  /** SplitMix64 finalizer (same mixer as {@code GauntletMatch}). */
  static long mix64(long z) {
    z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
    z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
    return z ^ (z >>> 31);
  }

  private static String env(String key, String fallback) {
    String v = System.getProperty(key, System.getenv(key));
    return v != null && !v.isBlank() ? v : fallback;
  }
}
