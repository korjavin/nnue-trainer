# NNUE Trainer & Bot for Virus Game (Java)

This is a Java Spring Boot application managed and built using Maven. It serves as an NNUE (Efficiently Updatable Neural Network) trainer and bot for the Virus game.

## Architecture

The project is structured into several key components:
- **Board**: Contains the logic, state representation, and validation for the Virus game board.
- **Protocol**: Implements a WebSocket JSON client to interact with the multiplayer game server.
- **NNUE**: Handles the neural network architecture, inference, and training processes for evaluating board states.

## Protocol

The bot communicates with the game server via a WebSocket connection. It sends and receives multiplayer JSON messages, which include game state updates, turn notifications, move submissions, and other events required to play a live game.

## Build & Run

To build the project, use the included Maven Wrapper:

```bash
./mvnw clean package
```

To run the application, you can execute the generated JAR or use the Spring Boot run command:

```bash
./mvnw spring-boot:run
```

To compile and run tests:

```bash
./mvnw compile
./mvnw test
```

*Note: The project requires Java 21 for compilation and runtime.*

## NNUE training pipeline (TD-leaf)

One pass of TD-leaf value iteration — warm-started self-play → dataset → trained
weights — is wrapped in `td_leaf_pass.sh`:

```bash
./td_leaf_pass.sh                       # smoke defaults
NUM_GAMES=200 SEARCH_DEPTH=4 TD_LAMBDA=0.3 SEED=2 ./td_leaf_pass.sh
```

It (1) runs `SelfPlayGenerator` in `TD_LEAF` mode — each position's target is our own
search's negamax value blended toward the game outcome by `TD_LAMBDA` (λ=1 → pure
outcome, λ=0 → pure search bootstrap), warm-started from `src/main/resources/nnue_weights.json`;
(2) writes the dataset to `$OUT` (default `dataset.json`); (3) runs `train.py`, which
prints val MSE vs the constant-predictor floor and exports the new weights back to
`src/main/resources/nnue_weights.json`. Re-run to iterate — each pass bootstraps off the
net the previous pass trained.

Knobs (env, small defaults): `NUM_GAMES`, `SEARCH_DEPTH`, `TD_LAMBDA`, `SEED`, `MAX_TURNS`,
`OUT`. The generator alone also honours these envs; run it without `td_leaf_pass.sh` via
`java -cp "target/classes:$(./mvnw -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout | tail -1)" com.engine.nnue_trainer.train.SelfPlayGenerator`.
Omitting `LABEL_MODE=TD_LEAF` reproduces the old outcome-label baseline.

### Phase 2: TD-leaf through the strong GoBot search

`td_leaf_pass_gobot.sh` is the same pass but with move selection AND the TD-leaf target
coming from `GoBotSearcher` (the strong ported GoBot search) with the NNUE net as its leaf,
instead of the negamax `SearchEngine`. Same search on both sides, only the leaf eval learns —
the clean "can a trained eval beat the hand-tuned one" test.

```bash
./td_leaf_pass_gobot.sh                                       # smoke defaults (60k node budget)
NUM_GAMES=200 GOBOT_NODE_LIMIT=60000 TD_LAMBDA=0.3 SEED=2 EPSILON=0.25 EXPLORE_TURNS=20 \
  ./td_leaf_pass_gobot.sh                                     # a strength pass
GOBOT_FIXED_DEPTH=3 ./td_leaf_pass_gobot.sh                   # fixed depth instead of node budget
```

GoBot knobs: `GOBOT_NODE_LIMIT` (per-move node budget, default 60000, the proven strong live
setting), `GOBOT_FIXED_DEPTH` (>0 uses a fixed depth instead), `EPSILON`/`EXPLORE_TURNS`
(diversity). Shared knobs (`NUM_GAMES`, `TD_LAMBDA`, `SEED`, `MAX_TURNS`, `OUT`) pass straight
through; `SEARCH_DEPTH` is unused in this mode.

**Evaluate the trained net (maintainer):** `eval_java_vs_go.py` inherits `SEARCH`/`EVAL` from the
environment (they reach the Java bot via `os.environ.copy()`), so the leaf eval is just an env var.
With the same GoBot search on both sides only the leaf eval differs, so any win-rate gap is pure
eval quality. Prereqs: sibling `../virusgame` built (`server`, `bot-hoster`), `JAVA_HOME` = JDK21.

```bash
# each bot vs the deterministic GoBot reference (result col: 1=Java win, 2=GoBot win)
SEARCH=GOBOT EVAL=NNUE      python3 eval_java_vs_go.py 20   # trained NNUE leaf vs GoBot
SEARCH=GOBOT EVAL=HANDTUNED python3 eval_java_vs_go.py 20   # hand-tuned leaf vs GoBot (Phase 1 6-0)
```

The harness is challenger-vs-GoBot, so `EVAL=NNUE` vs `EVAL=HANDTUNED` is read off the two win
rates above (both use the identical GoBot search — only the leaf eval, hence the gap, differs).

## Rules of Virus Game

The Virus game is a grid-based strategy game where players aim to control the board. The key rules are as follows:

- **Move Types**: Players can make two types of moves:
  - **Grow**: Expanding to an adjacent empty cell.
  - **Attack**: Taking over an opponent's adjacent cell.
- **Moves per Turn**: Each player is allowed to make 3 moves per turn.
- **Base Connection Requirement**: Cells must maintain an active connection to a base; isolated cells may be neutralized or lost.
- **Neutral Placement**: Neutral cells can be placed on the board according to specific game dynamics, often serving as obstacles or strategic points.
