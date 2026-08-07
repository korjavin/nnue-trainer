package com.engine.nnue_trainer.mcts;

import com.engine.nnue_trainer.board.Action;
import com.engine.nnue_trainer.board.Board;
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
 * Search-free self-play generator — {@link SelfPlayMcts}'s cheap sibling for early RL generations:
 * one policy-net forward per move (no tree), ~45x the games/min of 64-sim MCTS self-play.
 *
 * <p>Emits the <b>same</b> JSONL schema as {@code SelfPlayMcts} ({@code
 * g/sym/ml/nuo/nux/mover/pi/pv/z}); the only difference is that {@code pv} carries the policy's own
 * masked softmax distribution (floats) instead of root visit counts — the trainer normalizes {@code
 * pv} either way. {@code z} is the absolute-frame outcome from the single labeling rule, flipped
 * per-row by the trainer via {@code "mover"} exactly as before.
 *
 * <p>Move choice mirrors the MCTS generator's schedule: τ=1 sampling for the first {@link
 * SelfPlayMcts#TEMPERATURE_PLIES} plies, then argmax. Determinism contract is identical too:
 * per-game randomness derives from {@code (seed, gameIdx)} only, so output is fixed per (seed,
 * shard) regardless of shard count.
 *
 * <p>CLI: {@code SelfPlayPolicyOnly <out.jsonl> <games> <shardIdx> <shardCount> <seed>}. Env:
 * {@code MCTS_PRIOR} (default {@code mcts_policy.json}).
 */
public final class SelfPlayPolicyOnly {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final int BOARD = 12;
  private static final int MAX_PLIES = 100 * GoState.ACTIONS_PER_TURN;

  private SelfPlayPolicyOnly() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 5) {
      System.err.println(
          "usage: SelfPlayPolicyOnly <out.jsonl> <games> <shardIdx> <shardCount> <seed>");
      System.exit(2);
    }
    Path out = Path.of(args[0]);
    int games = Integer.parseInt(args[1]);
    int shardIdx = Integer.parseInt(args[2]);
    int shardCount = Integer.parseInt(args[3]);
    long seed = Long.parseLong(args[4]);

    String priorPath =
        System.getProperty(
            "MCTS_PRIOR", System.getenv().getOrDefault("MCTS_PRIOR", "mcts_policy.json"));
    PolicyNetPrior net = PolicyNetPrior.load(Path.of(priorPath));

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
                "po" + seed + "-" + g,
                SelfPlayMcts.mix64(seed ^ (0x9E3779B97F4A7C15L * (g + 1))),
                net,
                w,
                SelfPlayMcts.freshState());
        played++;
      }
    }
    double secs = (System.currentTimeMillis() - t0) / 1000.0;
    System.out.printf(
        "shard %d/%d: games %d, rows %d, policy-only, prior %s, %.1fs (%.2f s/game)%n",
        shardIdx, shardCount, played, rows, priorPath, secs, played == 0 ? 0 : secs / played);
  }

  /** One policy-only game; returns the number of rows written. Package-visible for tests. */
  static long playGame(
      String gameId, long gameSeed, PolicyNetPrior net, BufferedWriter w, GoState start)
      throws Exception {
    Random sampler = new Random(SelfPlayMcts.mix64(gameSeed ^ 0x5DEECE66DL));
    GoState state = start;
    List<ObjectNode> pending = new ArrayList<>();
    for (int ply = 0; ply < MAX_PLIES && !state.gameOver(); ply++) {
      List<Action> legal = state.legalActions();
      if (legal.isEmpty()) {
        break;
      }
      float[] p = net.priors(state, legal);
      if (legal.size() > 1) {
        pending.add(row(gameId, state, legal, p));
      }
      int pick =
          ply < SelfPlayMcts.TEMPERATURE_PLIES ? sample(p, sampler) : PolicyOnlySide.argmax(p);
      state = state.applyGenerated(legal.get(pick));
    }
    int z = (int) MctsSearcher.terminalValueAbs(state);
    for (ObjectNode n : pending) {
      n.put("z", z);
      w.write(MAPPER.writeValueAsString(n));
      w.newLine();
    }
    return pending.size();
  }

  /** Same row shape as {@code SelfPlayMcts.row}; pv is the masked policy distribution. */
  static ObjectNode row(String gameId, GoState state, List<Action> legal, float[] p) {
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
    for (int a = 0; a < legal.size(); a++) {
      pi.add(SelfPlayMcts.flatIndex(legal.get(a)));
      pv.add(p[a]);
    }
    return n;
  }

  static int sample(float[] p, Random random) {
    double r = random.nextDouble();
    for (int i = 0; i < p.length; i++) {
      r -= p[i];
      if (r < 0) {
        return i;
      }
    }
    return p.length - 1;
  }
}
