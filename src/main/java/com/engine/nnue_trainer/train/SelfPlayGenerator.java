package com.engine.nnue_trainer.train;

import com.engine.nnue_trainer.board.Action;
import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.board.MoveGenerator;
import com.engine.nnue_trainer.nnue.BoardFeatureMapper;
import com.engine.nnue_trainer.nnue.NNUEModel;
import com.engine.nnue_trainer.search.SearchEngine;
import com.engine.nnue_trainer.search.SearchResult;
import com.engine.nnue_trainer.search.gobot.GoBotSearcher;
import com.engine.nnue_trainer.search.gobot.GoResult;
import com.engine.nnue_trainer.search.gobot.GoState;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class SelfPlayGenerator {

  /** How a training record's target is labeled. */
  public enum LabelMode {
    /** Final game outcome mapped to ±1 (the original noisy label). */
    OUTCOME,
    /** TD-leaf: our own search's negamax value, blended toward the outcome by {@code tdLambda}. */
    TD_LEAF
  }

  /** Which search drives move selection (and the TD-leaf target). */
  public enum SearchMode {
    /** The negamax NNUE {@link SearchEngine} (original path). */
    NEGAMAX,
    /** The strong ported GoBot search with an NNUE leaf (Phase 2 Task 3). */
    GOBOT
  }

  public static class Config {
    public int numGames = 50;
    public int maxTurns = 100;
    public double epsilon = 0.1;
    public int exploreTurns = 6;
    public int searchDepth = 2;
    public long timeLimitMs = 0;
    public long seed = 0;
    public LabelMode labelMode = LabelMode.OUTCOME;

    /** TD-leaf blend: 1 → pure outcome (subsumes OUTCOME), 0 → pure search bootstrap. */
    public double tdLambda = 1.0;

    public SearchMode searchMode = SearchMode.NEGAMAX;

    /** GoBot mode node budget (0 disables); the proven strong live setting is 60k. */
    public long gobotNodeLimit = 60_000L;

    /** GoBot mode fixed depth; &gt;0 uses {@code chooseDepth} instead of the node budget. */
    public int gobotFixedDepth = 0;
  }

  public static class TrainingRecord {
    public float[] features;
    public float target;

    public TrainingRecord(float[] features, float target) {
      this.features = features;
      this.target = target;
    }
  }

  public static class GenerationResult {
    public List<TrainingRecord> dataset;
    public double distinctGameRatio;

    public GenerationResult(List<TrainingRecord> dataset, double distinctGameRatio) {
      this.dataset = dataset;
      this.distinctGameRatio = distinctGameRatio;
    }
  }

  private static class TurnData {
    public Board board;
    public int activePlayer;
    public boolean canPlaceNeutral;

    public TurnData(Board board, int activePlayer, boolean canPlaceNeutral) {
      this.board = board;
      this.activePlayer = activePlayer;
      this.canPlaceNeutral = canPlaceNeutral;
    }
  }

  public static void main(String[] args) throws IOException {
    Config config = new Config();
    String outputPath = "src/main/resources/self_play_data.json";

    if (args.length > 0) {
      try {
        config.numGames = Integer.parseInt(args[0]);
      } catch (NumberFormatException e) {
        System.err.println("Invalid number of games. Using default: " + config.numGames);
      }
    }
    if (args.length > 1) {
      outputPath = args[1];
    }

    // Env overrides (used by td_leaf_pass.sh) — env wins over positional args so a shell
    // wrapper can drive the whole TD-leaf pass without editing code.
    config.numGames = envInt("NUM_GAMES", config.numGames);
    config.maxTurns = envInt("MAX_TURNS", config.maxTurns);
    config.searchDepth = envInt("SEARCH_DEPTH", config.searchDepth);
    config.seed = envLong("SEED", config.seed);
    config.tdLambda = envDouble("TD_LAMBDA", config.tdLambda);
    // Exploration knobs — the default (epsilon 0.1, capped to the first 6 turns) makes both
    // deterministic engines converge to near-identical games (distinct-ratio ~0.02). Exposing
    // these lets us explore every turn (EXPLORE_TURNS high) at a higher rate for diverse data.
    config.epsilon = envDouble("EPSILON", config.epsilon);
    config.exploreTurns = envInt("EXPLORE_TURNS", config.exploreTurns);
    String mode = System.getenv("LABEL_MODE");
    if (mode != null && !mode.isBlank()) {
      config.labelMode = LabelMode.valueOf(mode.trim().toUpperCase());
    }
    // SELFPLAY_SEARCH=GOBOT routes move selection + TD-leaf targets through the strong GoBot search
    // (with the NNUE leaf); GOBOT_NODE_LIMIT / GOBOT_FIXED_DEPTH tune it (mirrors GameLoopHandler).
    String sm = System.getenv("SELFPLAY_SEARCH");
    if (sm != null && sm.trim().equalsIgnoreCase("GOBOT")) {
      config.searchMode = SearchMode.GOBOT;
    }
    config.gobotNodeLimit = envLong("GOBOT_NODE_LIMIT", config.gobotNodeLimit);
    config.gobotFixedDepth = envInt("GOBOT_FIXED_DEPTH", config.gobotFixedDepth);
    outputPath = System.getenv().getOrDefault("OUT", outputPath);

    System.out.println(
        "Starting self-play: games="
            + config.numGames
            + " depth="
            + config.searchDepth
            + " label="
            + config.labelMode
            + " lambda="
            + config.tdLambda);
    GenerationResult result = generate(config, null);
    System.out.println("Generation complete. Total records: " + result.dataset.size());
    System.out.println("Distinct game ratio: " + result.distinctGameRatio);
    saveDataset(result.dataset, outputPath);
  }

  private static int envInt(String key, int fallback) {
    String v = System.getenv(key);
    return (v == null || v.isBlank()) ? fallback : Integer.parseInt(v.trim());
  }

  private static long envLong(String key, long fallback) {
    String v = System.getenv(key);
    return (v == null || v.isBlank()) ? fallback : Long.parseLong(v.trim());
  }

  private static double envDouble(String key, double fallback) {
    String v = System.getenv(key);
    return (v == null || v.isBlank()) ? fallback : Double.parseDouble(v.trim());
  }

  public static GenerationResult generate(Config config, SearchEngine customEngine) {
    if (!Double.isFinite(config.tdLambda) || config.tdLambda < 0.0 || config.tdLambda > 1.0) {
      throw new IllegalArgumentException("tdLambda must be in [0,1], got " + config.tdLambda);
    }
    if (config.searchMode == SearchMode.GOBOT) {
      return generateViaGoBot(config);
    }
    List<TrainingRecord> dataset = new ArrayList<>();
    Random random = config.seed != 0 ? new Random(config.seed) : new Random();
    SearchEngine engine;
    if (customEngine != null) {
      engine = customEngine;
    } else if (config.labelMode == LabelMode.TD_LEAF) {
      // Warm-start engine: createDefault() loads nnue_weights.json (the ntd.8 distill), and the
      // custom-model ctor sets isCustomModel=true so scoring searches don't short-circuit to the
      // opening book (which would return score 0 for early positions).
      engine = new SearchEngine(NNUEModel.createDefault());
    } else {
      engine = new SearchEngine();
    }

    Set<Integer> uniquePositionHashes = new HashSet<>();
    int totalPositions = 0;

    for (int game = 1; game <= config.numGames; game++) {
      // System.out.println("Simulating game " + game + "/" + config.numGames);
      List<TurnData> turns = new ArrayList<>();
      Board board = new Board(12, 12);

      // Initialize bases
      board.setCell(0, 0, new Cell(1, CellKind.BASE));
      board.setCell(11, 11, new Cell(2, CellKind.BASE));

      int currentPlayer = 1;
      int winner = 0;

      for (int turn = 0; turn < config.maxTurns; turn++) {
        boolean canPlaceNeutral = true;
        for (int actionIdx = 0; actionIdx < 3; actionIdx++) {
          // Collect board snapshot BEFORE move
          turns.add(new TurnData(copyBoard(board), currentPlayer, canPlaceNeutral));

          List<Action> legalActions =
              MoveGenerator.getLegalActions(currentPlayer, board, canPlaceNeutral);
          if (legalActions.isEmpty()) {
            if (engine.isTerminal(board)) {
              winner = determineWinner(board);
            } else {
              winner = 3 - currentPlayer;
            }
            break;
          }

          Action chosenAction = null;
          if (turn <= config.exploreTurns && random.nextDouble() < config.epsilon) {
            // Exploration
            chosenAction = legalActions.get(random.nextInt(legalActions.size()));
          } else {
            // Exploitation
            if (engine.getNnueModel() != null) {
              chosenAction =
                  engine.findBestActionUsingModel(
                          board, currentPlayer, config.searchDepth, canPlaceNeutral)
                      .bestAction;
            } else {
              chosenAction =
                  SearchEngine.findBestAction(
                          board, currentPlayer, config.searchDepth, canPlaceNeutral)
                      .bestAction;
            }
            if (chosenAction == null) {
              chosenAction = legalActions.get(0); // fallback
            }
          }

          if (chosenAction instanceof com.engine.nnue_trainer.board.PlaceNeutralsAction) {
            canPlaceNeutral = false;
            board = SearchEngine.applyAction(board, currentPlayer, chosenAction);
            break; // turn ends immediately on placement
          } else {
            board = SearchEngine.applyAction(board, currentPlayer, chosenAction);
          }

          if (engine.isTerminal(board)) {
            winner = determineWinner(board);
            break;
          }
        }
        if (winner != 0) break;
        currentPlayer = 3 - currentPlayer;
      }

      // Process collected turns to dataset
      for (TurnData turnData : turns) {
        float target =
            computeTarget(
                engine,
                turnData.board,
                turnData.activePlayer,
                turnData.canPlaceNeutral,
                winner,
                config);
        float[] features = BoardFeatureMapper.map(turnData.board, turnData.activePlayer);
        dataset.add(new TrainingRecord(features, target));

        uniquePositionHashes.add(Arrays.hashCode(features));
        totalPositions++;
      }
    }

    double distinctGameRatio =
        totalPositions > 0 ? (double) uniquePositionHashes.size() / totalPositions : 0.0;
    return new GenerationResult(dataset, distinctGameRatio);
  }

  /** One recorded ply: the position (side-to-move oriented) plus the search's backed-up value. */
  private static final class GoPly {
    final float[] features;
    final int sideToMove;
    final float searchValue; // GoBot backed-up score, scaled to ±1

    GoPly(float[] features, int sideToMove, float searchValue) {
      this.features = features;
      this.sideToMove = sideToMove;
      this.searchValue = searchValue;
    }
  }

  /**
   * Phase 2 self-play where BOTH move selection and the TD-leaf target come from the strong GoBot
   * search using the current NNUE net as its leaf. Games are played through {@link GoState} (the
   * GoBot rules) rather than the negamax loop above; each ply is recorded with the search's
   * backed-up value (scaled to ±1) as its TD-leaf target, blended toward the outcome by {@code
   * tdLambda}. Honors {@code epsilon}/{@code exploreTurns} for diversity.
   */
  private static GenerationResult generateViaGoBot(Config config) {
    NNUEModel model = NNUEModel.createDefault();
    // Restore the caller's prior default (e.g. an EVAL=NNUE config from GameLoopHandler), not an
    // assumed HAND_TUNED, so we don't leak NNUE-leaf state yet also don't clobber a live config.
    GoBotSearcher.LeafConfig prev =
        GoBotSearcher.configureDefaultLeafEval(GoBotSearcher.LeafEval.NNUE, model);
    try {
      return playGoBotGames(config);
    } finally {
      GoBotSearcher.restoreDefaultLeafEval(prev);
    }
  }

  private static GenerationResult playGoBotGames(Config config) {
    List<TrainingRecord> dataset = new ArrayList<>();
    Random random = config.seed != 0 ? new Random(config.seed) : new Random();
    Set<Integer> uniquePositionHashes = new HashSet<>();
    int totalPositions = 0;
    int maxPlies = config.maxTurns * GoState.ACTIONS_PER_TURN;
    int exploreWindow = config.exploreTurns * GoState.ACTIONS_PER_TURN;

    for (int game = 1; game <= config.numGames; game++) {
      List<GoPly> plies = new ArrayList<>();
      GoState state = GoState.fromBoard(freshBoard(), 1, GoState.ACTIONS_PER_TURN, new boolean[2]);

      for (int ply = 0; ply < maxPlies && !state.gameOver(); ply++) {
        List<Action> legal = state.legalActions();
        if (legal.isEmpty()) {
          break; // stuck player — GoState elimination normally sets gameOver before we get here
        }
        GoResult r = chooseGoBot(state, config);
        // Opening-book moves (node-budget mode) carry a HandTunedEval score on the hand-tuned
        // magnitude scale, NOT an NNUE-leaf backed-up value; scoreToUnit (the NNUE_SCALE inverse)
        // would mislabel them with hand-tuned-derived targets. The book fires only on the
        // deterministic canonical opening, so skipping those plies loses no signal and keeps every
        // recorded target on the NNUE search's scale. A completed depth-0 GoResult is uniquely a
        // book move (real searches report depth >= 1).
        boolean fromBook = r != null && r.searchComplete && r.depth == 0;
        if (!fromBook) {
          float value = (r != null) ? GoBotSearcher.scoreToUnit(r.score) : 0f;
          plies.add(
              new GoPly(
                  BoardFeatureMapper.map(state.toBoard(), state.currentPlayer()),
                  state.currentPlayer(),
                  value));
        }

        Action chosen;
        if (ply < exploreWindow && random.nextDouble() < config.epsilon) {
          chosen = legal.get(random.nextInt(legal.size()));
        } else {
          chosen = (r != null && r.action != null) ? r.action : legal.get(0);
        }
        GoState next = state.apply(chosen);
        if (next == null) {
          chosen = legal.get(0);
          next = state.apply(chosen);
        }
        state = next;
      }

      int winner = state.gameOver() ? state.winner() : 0;
      for (GoPly p : plies) {
        float outcome = winner == 0 ? 0f : (winner == p.sideToMove ? 1f : -1f);
        float target =
            config.labelMode == LabelMode.TD_LEAF
                ? (float) ((1.0 - config.tdLambda) * p.searchValue + config.tdLambda * outcome)
                : outcome;
        dataset.add(new TrainingRecord(p.features, target));
        uniquePositionHashes.add(Arrays.hashCode(p.features));
        totalPositions++;
      }
    }

    double distinctGameRatio =
        totalPositions > 0 ? (double) uniquePositionHashes.size() / totalPositions : 0.0;
    return new GenerationResult(dataset, distinctGameRatio);
  }

  /** GoBot move selection: fixed depth when {@code gobotFixedDepth>0}, else the node budget. */
  private static GoResult chooseGoBot(GoState state, Config config) {
    if (config.gobotFixedDepth > 0) {
      return GoBotSearcher.chooseDepth(state, config.gobotFixedDepth);
    }
    return GoBotSearcher.chooseNodeBudget(state, config.gobotNodeLimit);
  }

  private static Board freshBoard() {
    Board board = new Board(12, 12);
    board.setCell(0, 0, new Cell(1, CellKind.BASE));
    board.setCell(11, 11, new Cell(2, CellKind.BASE));
    return board;
  }

  /**
   * Target label for one position, side-to-move ({@code activePlayer}) relative.
   *
   * <p>OUTCOME → final game result mapped to ±1 (0 if unfinished). TD_LEAF → {@code
   * (1-λ)·searchValue + λ·outcome}, where {@code searchValue} is our own search's negamax score
   * (already side-to-move relative). λ=1 reproduces OUTCOME; λ=0 is a pure search bootstrap.
   */
  static float computeTarget(
      SearchEngine engine,
      Board board,
      int activePlayer,
      boolean canPlaceNeutral,
      int winner,
      Config config) {
    float outcome = 0.0f;
    if (winner != 0) {
      outcome = (winner == activePlayer) ? 1.0f : -1.0f;
    }
    if (config.labelMode != LabelMode.TD_LEAF) {
      return outcome;
    }

    SearchResult result =
        engine.findBestActionUsingModel(board, activePlayer, config.searchDepth, canPlaceNeutral);
    float searchValue = result.score;
    // A forced win/loss inside the search returns ±Infinity; collapse to ±1 (and a stray NaN to 0)
    // so a non-finite score never poisons the dataset with a non-finite target.
    if (!Float.isFinite(searchValue)) {
      searchValue = Float.isNaN(searchValue) ? 0f : Math.signum(searchValue);
    }
    // Anchor to the ±1 outcome scale before blending: the net output is unbounded, and this label
    // is
    // re-fed as the next pass's training target (value iteration), so an out-of-range eval could
    // otherwise drift the net higher pass-over-pass with nothing to bound it.
    searchValue = Math.max(-1f, Math.min(1f, searchValue));
    double lambda = config.tdLambda;
    return (float) ((1.0 - lambda) * searchValue + lambda * outcome);
  }

  private static int determineWinner(Board board) {
    boolean player1BaseAlive = false;
    boolean player2BaseAlive = false;

    for (int r = 0; r < board.rows; r++) {
      for (int c = 0; c < board.cols; c++) {
        Cell cell = board.getCell(r, c);
        if (cell != null && cell.kind == CellKind.BASE) {
          if (cell.owner == 1) player1BaseAlive = true;
          if (cell.owner == 2) player2BaseAlive = true;
        }
      }
    }

    if (player1BaseAlive && !player2BaseAlive) return 1;
    if (!player1BaseAlive && player2BaseAlive) return 2;
    return 0; // Draw or both destroyed (shouldn't happen in standard play but just in case)
  }

  private static Board copyBoard(Board original) {
    Board copy = new Board(original.rows, original.cols);
    for (int r = 0; r < original.rows; r++) {
      for (int c = 0; c < original.cols; c++) {
        Cell cell = original.getCell(r, c);
        if (cell != null) {
          copy.setCell(r, c, new Cell(cell.owner, cell.kind));
        }
      }
    }
    return copy;
  }

  private static void saveDataset(List<TrainingRecord> dataset, String filepath)
      throws IOException {
    // Let IOException propagate: a failed write must exit non-zero so td_leaf_pass.sh (set -e)
    // stops instead of training against a stale/missing dataset.
    File file = new File(filepath);
    File parent = file.getParentFile();
    if (parent != null) {
      parent.mkdirs();
    }
    ObjectMapper mapper = new ObjectMapper();
    mapper.writerWithDefaultPrettyPrinter().writeValue(file, dataset);
    System.out.println("Dataset saved to " + filepath);
  }
}
