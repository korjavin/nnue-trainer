package com.engine.nnue_trainer.protocol;

import com.engine.nnue_trainer.board.Action;
import com.engine.nnue_trainer.board.Board;
import com.engine.nnue_trainer.board.Cell;
import com.engine.nnue_trainer.board.CellKind;
import com.engine.nnue_trainer.board.MoveAction;
import com.engine.nnue_trainer.board.PlaceNeutralsAction;
import com.engine.nnue_trainer.mcts.MctsSearcher;
import com.engine.nnue_trainer.mcts.PolicyNetPrior;
import com.engine.nnue_trainer.search.SearchEngine;
import com.engine.nnue_trainer.search.SearchResult;
import com.engine.nnue_trainer.search.gobot.GoBotExploration;
import com.engine.nnue_trainer.search.gobot.GoBotSearcher;
import com.engine.nnue_trainer.search.gobot.GoResult;
import com.engine.nnue_trainer.search.gobot.GoState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;

public class GameLoopHandler {
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final MessageSender messageSender;
  private final SearchEngine searchEngine;
  private int myPlayerIndex = -1;
  // volatile: written on the message worker thread, read from the challenger's scheduler thread.
  private volatile String currentGameId = "";

  public GameLoopHandler(MessageSender messageSender) {
    this(messageSender, new SearchEngine());
  }

  public GameLoopHandler(MessageSender messageSender, SearchEngine searchEngine) {
    this.messageSender = messageSender;
    this.searchEngine = searchEngine;
  }

  /** True while a game is in progress (used by the challenger to skip challenging mid-game). */
  public boolean isInGame() {
    return !currentGameId.isEmpty();
  }

  public void handleMessage(String jsonMessage) {
    try {
      JsonNode node = objectMapper.readTree(jsonMessage);
      if (!node.has("type")) return;
      String type = node.get("type").asText();

      if ("multiplayer_game_start".equals(type) || "game_start".equals(type)) {
        this.currentGameId = node.get("gameId").asText();
        this.myPlayerIndex = node.get("yourPlayer").asInt();
        System.out.println("Game started: gameId=" + currentGameId + ", myPlayer=" + myPlayerIndex);
        // game_start carries the initial snapshot; player 1 must move off it to start play.
        handleSnapshot(node);
      } else if ("move_made".equals(type)) {
        int player = node.get("player").asInt();
        if (player != myPlayerIndex) {
          int row = node.get("row").asInt();
          int col = node.get("col").asInt();
          System.out.println("Opponent played Move at (" + row + ", " + col + ")");
        }
        handleSnapshot(node);
      } else if ("neutrals_placed".equals(type)) {
        int player = node.get("player").asInt();
        if (player != myPlayerIndex) {
          JsonNode cells = node.get("cells");
          if (cells != null && cells.isArray() && cells.size() >= 2) {
            System.out.println(
                "Opponent placed Neutrals at "
                    + "("
                    + cells.get(0).get("row").asInt()
                    + ", "
                    + cells.get(0).get("col").asInt()
                    + ") and "
                    + "("
                    + cells.get(1).get("row").asInt()
                    + ", "
                    + cells.get(1).get("col").asInt()
                    + ")");
          } else {
            System.out.println("Opponent placed Neutrals");
          }
        }
        // Only act on OPPONENT neutral placements. Our own neutral consumes the whole turn, but
        // its ack snapshot still shows us as mover with moves left — searching off it fired a
        // rogue out-of-turn move, which fast (sub-second) searches turned into an accepted move
        // in our NEXT turn followed by a stale-state duplicate and an illegal_move forfeit
        // (2 live forfeits on 2026-08-08; the authoritative turn_change drives our real turns).
        if (player != myPlayerIndex) {
          handleSnapshot(node);
        }
      } else if ("turn_change".equals(type)) {
        if (node.has("snapshot")) {
          JsonNode snapshot = node.get("snapshot");
          int currentPlayer = snapshot.get("currentPlayer").asInt();
          int movesLeft = snapshot.get("movesLeft").asInt();
          System.out.println(
              "Turn changed: Player " + currentPlayer + "'s turn (Moves left: " + movesLeft + ")");
        }
        handleSnapshot(node);
      } else if ("game_end".equals(type)) {
        System.out.println("Game ended. Winner: player " + node.get("winner").asInt());
        this.currentGameId = "";
        this.myPlayerIndex = -1;
      }
    } catch (Exception e) {
      System.err.println("Error in GameLoopHandler: " + e.getMessage());
      e.printStackTrace();
    }
  }

  private void handleSnapshot(JsonNode node) {
    if (node.has("snapshot")) {
      JsonNode snapshot = node.get("snapshot");
      int currentPlayer = snapshot.get("currentPlayer").asInt();
      boolean gameOver = snapshot.get("gameOver").asBoolean();
      int movesLeft = snapshot.has("movesLeft") ? snapshot.get("movesLeft").asInt() : 0;

      if (!gameOver && currentPlayer == myPlayerIndex && movesLeft > 0) {
        Board board = parseBoardFromSnapshot(snapshot);
        boolean[] neutralUsed = parseNeutralUsed(snapshot);
        // One pair per player per game AND a turn-opening action only — the server rule ported in
        // GoState.legalActions. Without the movesLeft gate the SEARCH=NEGAMAX path could answer a
        // mid-turn snapshot with a neutrals move, which the server rejects as an illegal move.
        boolean canPlaceNeutral =
            movesLeft == GoState.ACTIONS_PER_TURN && !neutralUsed[myPlayerIndex - 1];
        // Feed the hand-tuned eval the root turn's non-board state (no-op for NNUE).
        searchEngine.setHandTunedState(movesLeft, neutralUsed);
        makeMove(snapshot, board, canPlaceNeutral, neutralUsed);
      }
    }
  }

  /** Parse the per-player {@code neutralUsed} flags (length = player count) from a snapshot. */
  static boolean[] parseNeutralUsed(JsonNode snapshot) {
    JsonNode neutralNode = snapshot.get("neutralUsed");
    boolean[] neutralUsed = new boolean[neutralNode.size()];
    for (int i = 0; i < neutralNode.size(); i++) {
      neutralUsed[i] = neutralNode.get(i).asBoolean();
    }
    return neutralUsed;
  }

  /**
   * Build the search {@link GoState} from a server snapshot with the SAME inputs the live GOBOT
   * path feeds {@link GoState#fromBoard} (board orientation, current player, movesLeft, per-player
   * neutralUsed). The live GOBOT path ({@link #gobotSearch}) builds through this method, and the
   * {@code handleSnapshot} guard pins {@code snapshot.currentPlayer} equal to {@code
   * myPlayerIndex}; so this is the single construction point the parity oracle ({@code
   * GoStateFromSnapshotTest}) asserts against.
   */
  static GoState goStateFromSnapshot(JsonNode snapshot) {
    int currentPlayer = snapshot.get("currentPlayer").asInt();
    int movesLeft = snapshot.has("movesLeft") ? snapshot.get("movesLeft").asInt() : 0;
    return GoState.fromBoard(
        parseBoardFromSnapshot(snapshot), currentPlayer, movesLeft, parseNeutralUsed(snapshot));
  }

  private static Board parseBoardFromSnapshot(JsonNode snapshot) {
    int rows = snapshot.get("rows").asInt();
    int cols = snapshot.get("cols").asInt();
    Board board = new Board(rows, cols);
    JsonNode boardNode = snapshot.get("board");
    for (int r = 0; r < rows; r++) {
      JsonNode rowNode = boardNode.get(r);
      for (int c = 0; c < cols; c++) {
        JsonNode cellNode = rowNode.get(c);

        JsonNode ownerNode = cellNode.has("owner") ? cellNode.get("owner") : cellNode.get("Owner");
        JsonNode kindNode = cellNode.has("kind") ? cellNode.get("kind") : cellNode.get("Kind");

        int owner = ownerNode != null ? ownerNode.asInt() : 0;
        CellKind kind = CellKind.EMPTY;

        if (kindNode != null) {
          String kindStr = kindNode.asText().toUpperCase();
          try {
            kind = CellKind.valueOf(kindStr);
          } catch (IllegalArgumentException e) {
            try {
              int val = Integer.parseInt(kindStr);
              for (CellKind k : CellKind.values()) {
                if (k.value == val) {
                  kind = k;
                  break;
                }
              }
            } catch (NumberFormatException nfe) {
              // Ignore
            }
          }
        }
        board.setCell(r, c, new Cell(owner, kind));
      }
    }
    return board;
  }

  // SEARCH=GOBOT selects the ported GoBot search (book -> iterative-deepening minimax -> HandTuned
  // leaf); with EVAL=HANDTUNED that is a GoBot clone by construction. Mirrors EVAL detection.
  // Read at construction (not class-load) so the SEARCH flag is honoured per instance and testable.
  private final boolean useGobotSearch = gobotSearchFromEnv();

  // SEARCH=MCTS selects the PUCT searcher with the trained policy prior — the deployment path for
  // promoted RL champions (mcts_champion.json). Read at construction like useGobotSearch.
  private final boolean useMctsSearch = "MCTS".equalsIgnoreCase(envOrProp("SEARCH"));

  // Env-gated, seeded near-best exploration for the data-gen challenger (opt-in only). Read at
  // construction like useGobotSearch. Default OFF ⇒ byte-identical deterministic best-move play.
  private final GoBotExploration exploration =
      GoBotExploration.fromEnv(
          "CHALLENGER_EXPLORE", "CHALLENGER_EXPLORE_TEMP", "CHALLENGER_EXPLORE_SEED", 0.6);

  // Per-move node budget for the deterministic live GoBot search. ~GoBot's 1s worth of nodes and
  // then some (GoBot does ~17-55k/move); at 60k the clone beats GoBot 6-0. Overridable via env.
  private static final long DEFAULT_LIVE_NODE_LIMIT = 60000L;

  private static boolean gobotSearchFromEnv() {
    // Default to the STRONGEST config (GoBot search + hand-tuned leaf = beats GoBot 6-0) with no
    // env needed — production must always run the strongest by default. Opt out with SEARCH=NEGAMAX
    // (or any non-GOBOT value) to use the legacy negamax NNUE search.
    String v = System.getProperty("SEARCH", System.getenv("SEARCH"));
    return v == null || v.isBlank() || "GOBOT".equalsIgnoreCase(v);
  }

  // EVAL=NNUE (with SEARCH=GOBOT) swaps the GoBot search's leaf eval to the learned NNUE net;
  // EVAL=HANDTUNED / unset keeps the hand-tuned leaf (the GoBot clone). Mirrors SearchEngine's EVAL
  // flag detection. Configured once at class load so every static GoBot entry point picks it up.
  static {
    String searchFlag = System.getProperty("SEARCH", System.getenv("SEARCH"));
    String evalFlag = System.getProperty("EVAL", System.getenv("EVAL"));
    if (gobotLeafEvalFor(searchFlag, evalFlag) == GoBotSearcher.LeafEval.NNUE) {
      GoBotSearcher.configureDefaultLeafEval(
          GoBotSearcher.LeafEval.NNUE, com.engine.nnue_trainer.nnue.NNUEModel.createDefault());
    }
    String warning = unwiredEvalWarning(searchFlag, evalFlag);
    if (warning != null) {
      System.err.println(warning);
    }
  }

  /**
   * Warning text when {@code EVAL} names a leaf this handler does not wire (v2, v3, typos) while
   * the GoBot search is active, else {@code null}. Silence there would run the hand-tuned leaf
   * while a harness reports the results as that eval's — see {@code docs/nnue-v3-runtime.md}.
   */
  static String unwiredEvalWarning(String searchFlag, String evalFlag) {
    if (evalFlag == null || evalFlag.isBlank() || "HANDTUNED".equalsIgnoreCase(evalFlag)) {
      return null;
    }
    // Same default-to-GOBOT rule as gobotSearchFromEnv().
    boolean gobot =
        searchFlag == null || searchFlag.isBlank() || "GOBOT".equalsIgnoreCase(searchFlag);
    // Ask the same resolver the static block uses, so "wired" can never drift from what is actually
    // configured — notably EVAL=NNUE with SEARCH unset, which runs GoBot with a hand-tuned leaf.
    if (!gobot || gobotLeafEvalFor(searchFlag, evalFlag) == GoBotSearcher.LeafEval.NNUE) {
      return null;
    }
    return "WARNING: EVAL="
        + evalFlag
        + " is not wired into the GoBot leaf (only an explicit SEARCH=GOBOT with EVAL=NNUE is);"
        + " the leaf stays hand-tuned. Use SEARCH=NEGAMAX for the SearchEngine EVAL flags, or"
        + " configure the leaf programmatically.";
  }

  /** Pure flag resolution: NNUE leaf only when {@code SEARCH=GOBOT} and {@code EVAL=NNUE}. */
  static GoBotSearcher.LeafEval gobotLeafEvalFor(String searchFlag, String evalFlag) {
    if ("GOBOT".equalsIgnoreCase(searchFlag) && "NNUE".equalsIgnoreCase(evalFlag)) {
      return GoBotSearcher.LeafEval.NNUE;
    }
    return GoBotSearcher.LeafEval.HAND_TUNED;
  }

  /**
   * Translate a chosen {@link Action} into the server move message, the sole tested translation
   * point. Mirrors GoBot's {@code actionMessage} (bot_client.go): a {@link MoveAction} sends {@code
   * {type:"move", row, col}} (the server infers grow vs attack from the board); a {@link
   * PlaceNeutralsAction} sends {@code {type:"neutrals", cells:[{row,col},{row,col}]}}.
   */
  static void writeAction(ObjectNode response, ObjectMapper mapper, Action action) {
    if (action instanceof MoveAction) {
      MoveAction move = (MoveAction) action;
      response.put("type", "move");
      response.put("row", move.target.row);
      response.put("col", move.target.col);
    } else if (action instanceof PlaceNeutralsAction) {
      PlaceNeutralsAction place = (PlaceNeutralsAction) action;
      response.put("type", "neutrals");
      response.set(
          "cells",
          mapper
              .createArrayNode()
              .add(mapper.createObjectNode().put("row", place.pos1.row).put("col", place.pos1.col))
              .add(
                  mapper.createObjectNode().put("row", place.pos2.row).put("col", place.pos2.col)));
    }
  }

  private void makeMove(
      JsonNode snapshot, Board board, boolean canPlaceNeutral, boolean[] neutralUsed) {
    SearchResult searchResult =
        useMctsSearch
            ? mctsSearch(snapshot, board, canPlaceNeutral, neutralUsed)
            : useGobotSearch
                ? gobotSearch(snapshot, neutralUsed)
                : searchEngine.findBestActionWithTimeLimitUsingModel(
                    board, myPlayerIndex, 5000, canPlaceNeutral);
    Action bestAction = searchResult.bestAction;

    System.out.println(
        "[SEARCH] depth="
            + searchResult.depth
            + " nodes="
            + searchResult.nodesEvaluated
            + " timeMs="
            + searchResult.timeMs
            + " score="
            + searchResult.score);

    if (bestAction == null) {
      System.out.println("No legal actions available.");
      return;
    }

    try {
      ObjectNode response = objectMapper.createObjectNode();
      response.put("gameId", currentGameId);

      // Append diagnostics payload
      double scoreToSend = searchResult.score;
      if (scoreToSend == Float.POSITIVE_INFINITY) {
        scoreToSend = 1000000.0;
      } else if (scoreToSend == Float.NEGATIVE_INFINITY) {
        scoreToSend = -1000000.0;
      }
      response.put("score", scoreToSend * 1000.0);
      response.put("depth", searchResult.depth);
      response.put("nodesEvaluated", searchResult.nodesEvaluated);
      response.put("timeMs", searchResult.timeMs);

      writeAction(response, objectMapper, bestAction);
      if (bestAction instanceof MoveAction) {
        MoveAction move = (MoveAction) bestAction;
        System.out.println("Playing Move: (" + move.target.row + ", " + move.target.col + ")");
      } else if (bestAction instanceof PlaceNeutralsAction) {
        PlaceNeutralsAction place = (PlaceNeutralsAction) bestAction;
        System.out.println(
            "Placing Neutrals: ("
                + place.pos1.row
                + ", "
                + place.pos1.col
                + "), ("
                + place.pos2.row
                + ", "
                + place.pos2.col
                + ")");
      }

      messageSender.send(objectMapper.writeValueAsString(response));
    } catch (Exception e) {
      System.err.println("Failed to send action: " + e.getMessage());
    }
  }

  // Persistent enhanced searcher (plan item 2): the TT carries over between this bot's moves for
  // the whole game. Recreated only if the mover changes (it never does — handleSnapshot pins
  // snapshot.currentPlayer == myPlayerIndex); stale entries from earlier positions age out via
  // TT generations.
  private GoBotSearcher liveGobotSearcher;

  private GoBotSearcher liveSearcher(GoState gs) {
    if (liveGobotSearcher == null || liveGobotSearcher.rootPlayer() != gs.currentPlayer()) {
      liveGobotSearcher = GoBotSearcher.newEnhancedSearcher(gs);
    }
    return liveGobotSearcher;
  }

  /** Run the ported GoBot search and adapt its {@link GoResult} into a {@link SearchResult}. */
  private SearchResult gobotSearch(JsonNode snapshot, boolean[] neutralUsed) {
    long start = System.currentTimeMillis();
    // GoState.fromBoard builds a 1v1 (players 1,2) state — the only mode SEARCH=GOBOT supports.
    // neutralUsed is per-player, so its length is the game's player count. Anything above 2 would
    // yield a state where player 3/4 is inactive (silent forfeit), so refuse loudly instead.
    if (neutralUsed != null && neutralUsed.length > 2) {
      System.err.println(
          "SEARCH=GOBOT supports 1v1 only; got " + neutralUsed.length + " players — no move made.");
      return new SearchResult(null, 0, 0, 0, System.currentTimeMillis() - start);
    }
    // Build the live GoState through the same tested seam GoStateFromSnapshotTest asserts against
    // (handleSnapshot pins snapshot.currentPlayer == myPlayerIndex).
    // Live search uses the DETERMINISTIC, parity-verified node-budget entry by default. The 0-10
    // choose() loss investigated as bd 0dj.7 was NOT a move-selection bug — choose(deadline)
    // returns exactly the chooseDepth move of the last fully completed iteration (see
    // GoBotChooseDeadlineConsistencyTest) — but a compute asymmetry: 1s wall clock buys ~15-20k
    // nodes on a training-loaded box, while chooseNodeBudget(60k) silently spends 3-6s/move, so
    // time mode plays 1-2 plies shallower. Node budget stays the default: deterministic and not
    // starved by machine load. Overridable via env for experiments.
    GoState gs = goStateFromSnapshot(snapshot);
    GoResult r;
    String fd = System.getenv("GOBOT_FIXED_DEPTH");
    String nl = System.getenv("GOBOT_NODE_LIMIT");
    String tm = System.getenv("GOBOT_TIME_MODE"); // opt into wall-clock choose() (load-sensitive)
    if (fd != null && !fd.isBlank()) {
      r = GoBotSearcher.chooseDepth(gs, Integer.parseInt(fd.trim()));
    } else if (tm != null && !tm.isBlank()) {
      r = liveSearcher(gs).search(gs);
    } else {
      long limit =
          (nl != null && !nl.isBlank()) ? Long.parseLong(nl.trim()) : DEFAULT_LIVE_NODE_LIMIT;
      r = liveSearcher(gs).searchNodeBudget(gs, limit);
    }
    if (r == null) {
      // No legal action from this position; let makeMove log "No legal actions available."
      return new SearchResult(null, 0, 0, 0, System.currentTimeMillis() - start);
    }
    // Exploration (opt-in): a book move (searchComplete && depth==0) is randomized over legal
    // openings; any other result is softmax near-best sampled. Disabled ⇒ chosen == r.action.
    Action chosen;
    if (exploration.enabled && r.searchComplete && r.depth == 0) {
      Action opening = exploration.sampleOpening(gs.legalActions());
      chosen = opening != null ? opening : r.action;
    } else {
      chosen = exploration.sampleMove(r);
    }
    return new SearchResult(
        chosen, r.score, r.depth, (int) r.nodes, System.currentTimeMillis() - start);
  }

  // --- SEARCH=MCTS: live PUCT search with the trained policy prior ---

  // Loaded once per handler on the first MCTS move (like liveGobotSearcher); null after a load
  // attempt means no artifact was found and every move falls back to the GoBot search.
  private MctsSearcher.Config mctsLiveConfig;
  private boolean mctsLiveConfigLoaded;

  private static String envOrProp(String key) {
    return System.getProperty(key, System.getenv(key));
  }

  private static String envOrProp(String key, String fallback) {
    String v = envOrProp(key);
    return v != null && !v.isBlank() ? v : fallback;
  }

  /**
   * Build the live MCTS config from env, or {@code null} when no prior artifact loads (the caller
   * then falls back to the GoBot search — same graceful-degradation contract as EVAL=NNUEV3).
   * {@code MCTS_PRIOR} names the artifact; unset tries the promoted {@code mcts_champion.json}
   * first, then the committed Phase 1 {@code mcts_policy.json}. {@code MCTS_VALUE=net} enables the
   * artifact's value head when present; {@code MCTS_CPUCT} overrides the exploration constant. Root
   * noise stays OFF (play mode, the Config default).
   */
  static MctsSearcher.Config loadMctsConfig() {
    String configured = envOrProp("MCTS_PRIOR", "");
    String[] candidates =
        configured.isBlank()
            ? new String[] {"mcts_champion.json", "mcts_policy.json"}
            : new String[] {configured};
    PolicyNetPrior prior = null;
    String loadedFrom = null;
    for (String p : candidates) {
      if (!Files.exists(Path.of(p))) {
        continue;
      }
      try {
        prior = PolicyNetPrior.load(Path.of(p));
        loadedFrom = p;
        break;
      } catch (Exception e) {
        System.err.println("SEARCH=MCTS: failed to load prior " + p + ": " + e.getMessage());
      }
    }
    if (prior == null) {
      return null;
    }
    MctsSearcher.Config config = new MctsSearcher.Config();
    config.prior = prior;
    config.cpuct = Double.parseDouble(envOrProp("MCTS_CPUCT", "1.5"));
    if ("net".equalsIgnoreCase(envOrProp("MCTS_VALUE", ""))) {
      if (prior.hasValueHead()) {
        config.valueNet = prior;
      } else {
        System.err.println(
            "SEARCH=MCTS: MCTS_VALUE=net but "
                + loadedFrom
                + " has no value_head; using the hand-tuned leaf value.");
      }
    }
    System.out.println(
        "SEARCH=MCTS: prior="
            + loadedFrom
            + (config.valueNet != null ? "+value" : "")
            + " cpuct="
            + config.cpuct);
    return config;
  }

  /**
   * Run the PUCT searcher from the same tested {@link #goStateFromSnapshot} seam as the GoBot path
   * under a wall-clock budget ({@code MCTS_MOVE_MILLIS}, default 1000 — the gauntlet's production
   * condition). Falls back rather than crashing: non-12x12 or >2 players (the prior net is 12x12
   * 1v1 only) takes the legacy negamax path; a missing prior artifact takes the GoBot path.
   */
  private SearchResult mctsSearch(
      JsonNode snapshot, Board board, boolean canPlaceNeutral, boolean[] neutralUsed) {
    if (board.rows != 12 || board.cols != 12 || (neutralUsed != null && neutralUsed.length > 2)) {
      System.err.println(
          "SEARCH=MCTS supports 12x12 1v1 only; got "
              + board.rows
              + "x"
              + board.cols
              + " — using the negamax path.");
      return searchEngine.findBestActionWithTimeLimitUsingModel(
          board, myPlayerIndex, 5000, canPlaceNeutral);
    }
    if (!mctsLiveConfigLoaded) {
      mctsLiveConfigLoaded = true;
      mctsLiveConfig = loadMctsConfig();
      if (mctsLiveConfig == null) {
        System.err.println(
            "WARNING: SEARCH=MCTS but no prior artifact loaded (MCTS_PRIOR /"
                + " mcts_champion.json / mcts_policy.json) — falling back to the GoBot search.");
      }
    }
    if (mctsLiveConfig == null) {
      return gobotSearch(snapshot, neutralUsed);
    }
    long start = System.currentTimeMillis();
    GoState gs = goStateFromSnapshot(snapshot);
    long budget = Long.parseLong(envOrProp("MCTS_MOVE_MILLIS", "1000"));
    MctsSearcher searcher = new MctsSearcher(gs, mctsLiveConfig);
    searcher.runUntilDeadline(start + budget);
    Action action = searcher.bestAction();
    long elapsed = System.currentTimeMillis() - start;
    if (action == null) {
      // No legal action; let makeMove log "No legal actions available."
      return new SearchResult(null, 0, 0, 0, elapsed);
    }
    // Root value is absolute-frame (positive = good for player 1); report the mover's frame like
    // the other paths. depth has no MCTS analogue (0); sims map to the nodes diagnostic.
    double v = searcher.rootValueAbs();
    float score = (float) (gs.currentPlayer() == 1 ? v : -v);
    return new SearchResult(action, score, 0, searcher.simsRun(), elapsed);
  }
}
