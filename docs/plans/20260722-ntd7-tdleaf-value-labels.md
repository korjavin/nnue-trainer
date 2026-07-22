# Phase-2 eval: TD-leaf value labels for the NNUE (bd nnue-trainer-ntd.7)

## Overview

The NNUE eval is the bottleneck for GoBot parity, but NOT because of raw eval quality:
- `ntd.8` distilled GoBot's **static eval** into the net at val MSE 0.015 (near-perfect fit),
  yet the bot lost **0/7** vs GoBot. A static eval only wins when co-adapted to the search
  that uses it; ours isn't.
- `raz.2.5` showed our search is correct and deep (depth 8–12) but deeper search adds no wins.

The fix is **phase-2 RL/TD**: relabel training positions with a **TD-leaf** target — the value
our OWN search backs up from a position (its negamax leaf value), optionally blended toward the
final game outcome — instead of the noisy `±1` final result. The eval then learns to be good
**for our search specifically**, breaking the eval/search co-design mismatch. The distilled
weights (`nnue_weights.json`) are the warm-start.

**Scope of THIS plan (build the mechanism, not the full RL loop):** implement the TD-leaf
labeling pipeline end-to-end, fix the portability bugs that block running it on Linux, and
produce one trained net from one self-play → label → train → export pass, with unit tests.
The multi-iteration RL experiment and GoBot win-rate measurement are run by the maintainer
after this lands (see Post-Completion) — do NOT attempt long training/eval loops in-agent.

## Context (from discovery)

- `src/main/java/com/engine/nnue_trainer/train/SelfPlayGenerator.java` — already self-plays with
  `SearchEngine` and emits `TrainingRecord{float[] features, float target}`. Target is currently
  **outcome-based** (winner → ±1). This is where TD-leaf labeling goes.
- `src/main/java/com/engine/nnue_trainer/search/SearchEngine.java` — `SearchResult.score` is the
  negamax value (leaf eval backed up), relative to side-to-move. That score IS the TD-leaf value.
- `import_games.py:135` — the noisy label `target = 1.0 if result == player else -1.0` (offline
  path from DB games). `map_board_to_features` is the shared 864-feature encoding.
- `train.py` — trains 864→HIDDEN→1 (MSE, val split, reports val MSE vs constant-predictor floor),
  exports `hiddenWeights/hiddenBiases/outputWeights/outputBias`. **BUG: hardcoded macOS paths**
  `DATASET`/`OUT_PATH` = `/Users/iv/...` (line ~32) — breaks on this Linux host.
- `make_distill_dataset.py` — also has a hardcoded `/Users/iv/...` OUT path.
- Warm-start weights: `src/main/resources/nnue_weights.json` (the ntd.8 distill).
- Eval harness (maintainer will use post-merge): `eval_java_vs_go.py`, `.agents/skills/eval-vs-gobot/`.

## Development Approach

- **Regular (code first, then verify the pipeline runs).**
- Keep the outcome-label path intact for A/B — add TD-leaf as a **mode**, don't rip out the old one.
- Small commits. The acceptance gate here is **the pipeline runs end-to-end and produces
  exportable weights + passing unit tests** — NOT a GoBot win rate (that's the maintainer's
  experiment). Do not run multi-hour training or GoBot games.

## Testing Strategy

- Unit-test the TD-leaf target computation in `SelfPlayGeneratorTest` (a position's target equals
  the search-backed value / the correct blend, correct sign relative to side-to-move).
- A small smoke run (few games, shallow depth, tiny epoch count) that exercises
  self-play → dataset → `train.py` → weights export without erroring.
- Keep the full existing suite green; keep the export format byte-compatible with `NNUEModel.java`.

## Progress Tracking

- Mark `[x]` immediately. `➕` new tasks, `⚠️` blockers. Keep this file in sync.

## Implementation Steps

### Task 1: Fix Python pipeline portability
- [x] replace hardcoded `/Users/iv/...` paths in `train.py` (`DATASET`, `OUT_PATH`) and
      `make_distill_dataset.py` (`OUT`) with repo-relative paths (resolve from the script location)
      and/or CLI args, defaulting to `./dataset.json` and `./src/main/resources/nnue_weights.json`
      — now resolved from `__file__`, overridable via `DATASET`/`OUT_PATH` env (train.py) and
      argv[2]/`OUT` env (make_distill_dataset.py)
- [x] verify `train.py` runs on a tiny dataset on Linux (numpy already present) — 40-row tiny
      dataset trains + exports the 4-key weights JSON (hidden=256) without error
- [x] no test framework needed here; a successful tiny run is the check

### Task 2: TD-leaf labeling in SelfPlayGenerator
- [x] add a labeling mode to `SelfPlayGenerator.Config` (e.g. `LabelMode { OUTCOME, TD_LEAF }`,
      plus a `tdLambda` blend in [0,1]) — default OUTCOME to preserve current behavior
      — added `LabelMode` enum + `labelMode`/`tdLambda` fields, default OUTCOME/λ=1
- [x] in TD_LEAF mode, set each record's `target` to the position's search-backed value
      (`SearchResult.score` from a depth-`searchDepth` search with the loaded net), oriented to the
      side to move; blend toward the final outcome by `tdLambda` (λ=1 → pure outcome, λ=0 → pure
      bootstrap) so the mode subsumes the old behavior at λ=1
      — `computeTarget()` does `(1-λ)·searchValue + λ·outcome`; ±Inf search values collapse to ±1
- [x] load the warm-start net (`nnue_weights.json`) so bootstrapped targets come from the current
      eval, not a random net — TD_LEAF path builds `new SearchEngine(NNUEModel.createDefault())`,
      which loads `nnue_weights.json` and is a custom model (so scoring searches skip the opening book)
- [x] write `SelfPlayGeneratorTest` cases: TD_LEAF target equals the search value at λ=0, equals
      ±outcome at λ=1, and has the correct side-to-move sign — 3 new tests, all green
- [x] run `./mvnw test` — must pass before next task — full suite 73/73 green

### Task 3: Wire the one-pass pipeline + docs
- [x] provide a runnable path (a small script or documented commands) that: generates a TD-leaf
      self-play dataset (warm-started) → writes `dataset.json` → `train.py` → exports weights
      — `td_leaf_pass.sh`: compile → `SelfPlayGenerator` (TD_LEAF) → `train.py` → weights export
- [x] make dataset/searchDepth/games/λ configurable via args or env, with sane small defaults
      — `SelfPlayGenerator.main` now reads `NUM_GAMES`/`SEARCH_DEPTH`/`TD_LAMBDA`/`SEED`/`MAX_TURNS`/
      `OUT`/`LABEL_MODE` envs (env wins over positional args); script defaults to a fast smoke run
- [x] document the exact commands in the plan / a short README so the maintainer can iterate
      — added an "NNUE training pipeline (TD-leaf)" section to `README.md` with the iterate loop
- [x] run `./mvnw test` — green — full suite 73/73

### Task 4: Verify pipeline (mechanism gate, NOT strength)
- [x] `./mvnw test` all green; export format unchanged (loads in `NNUEModel`/`NNUEModelTest`)
      — full suite 73/73 green, NNUEModelTest 4/4
- [x] one SMALL smoke: few self-play games at shallow depth → train few epochs → weights written;
      confirm `train.py` prints val MSE vs the constant-predictor floor without error
      — `td_leaf_pass.sh` with NUM_GAMES=4 SEARCH_DEPTH=2 TD_LAMBDA=0.5 → 605 positions, weights
      written; `train.py --sweep` prints floor 0.279 vs best val MSE 0.013 (no error). Smoke-run
      weights reverted to keep the ntd.8 distill warm-start intact.
- [x] `./mvnw spotless:check` clean — BUILD SUCCESS

### Task 5: Update plan notes
- [ ] record the smoke val MSE and the exact iterate-commands for the maintainer

## Technical Details

- TD-leaf(λ) target for position p with side-to-move s:
  `target = (1-λ)·searchValue(p, s) + λ·outcome(p, s)` where `searchValue` is `SearchResult.score`
  (negamax, side-to-move relative) and `outcome(p,s) = +1` if s won the game else `-1`.
- Iterating this (generate with net N → train N+1 → repeat) is value iteration; each pass makes the
  eval better-matched to our search. THIS plan delivers one pass + the knobs to iterate.

## Post-Completion
*No checkboxes — maintainer-run experiment after merge:*

- Maintainer runs the multi-iteration loop (self-play → TD-leaf label → train → export) and measures
  GoBot win rate via `eval_java_vs_go.py` (reliable now that bd 0c6 is fixed), comparing to the
  outcome-label baseline (0/7) and sweeping λ / searchDepth. That experiment, not a unit test, is
  the real acceptance for "eval improved."
- Do NOT modify the distilled warm-start weights' provenance; keep the archived copy in virusgame.
