# Phase 2: Learned NNUE eval on the GoBot search (bd nnue-trainer-0dj.3)

## Overview

Step 1 is done: the Java GoBot clone (`SEARCH=GOBOT EVAL=HANDTUNED`, deterministic node-budget)
**beats GoBot 6-0**. Now build the apparatus for the real goal — a **learned NNUE eval that,
used BY the same strong GoBot search, beats GoBot's hand-tuned eval**. This is the cleanest
possible test of "can a trained eval exceed the hand-tuned one": SAME search on both sides, only
the leaf eval differs. Every prior NNUE attempt was confounded by a weak/broken search; this one
isn't.

**Scope (ralphex-buildable):** make the GoBot search's leaf eval swappable (hand-tuned | NNUE),
wire selection, and generate self-play training data THROUGH the GoBot search (so the data and the
TD-leaf targets come from the strong search). The actual **training + does-it-beat-GoBot
experiment is maintainer-run** (I run training/evals — the outcome is genuine research, uncertain).

## Context (from discovery)

- **GoBot search leaf eval:** `search/gobot/GoBotSearcher.java` — `leafEval(state)` (line ~267)
  hardcodes `HandTunedEval.staticEval(...)` returning an **int**. Score scale: `MATE_SCORE = 1e9`,
  `INF_SCORE = 2^60`; terminal/leaf scores stay within `±MATE_SCORE`. Any NNUE leaf value must be
  **scaled to a comparable integer range and clamped strictly below `MATE_SCORE`** (so it never
  collides with terminal/mate scores), oriented to the side-to-move, monotone-in-advantage.
- **NNUE eval API:** `nnue/NNUEModel.java` — `float forward(float[] input)` / `forward(Accumulator)`
  (output ~±1). `nnue/BoardFeatureMapper.map(board, player)` produces the 864 features. Warm-start
  weights: `src/main/resources/nnue_weights.json`.
- **Self-play:** `train/SelfPlayGenerator.java` currently uses `SearchEngine` (the negamax NNUE
  search), NOT the GoBot search. It already supports `LabelMode {OUTCOME, TD_LEAF}`, `tdLambda`, and
  the diversity knobs `EPSILON`/`EXPLORE_TURNS`. Phase 2 needs a mode where **move selection AND the
  TD-leaf target come from the GoBot search** (the strong one).
- **Pipeline:** `td_leaf_pass.sh` drives self-play → `train.py` → export. `eval_java_vs_go.py`
  (maintainer) evaluates vs GoBot; `SEARCH=GOBOT`/`EVAL=*` selection lives in `GameLoopHandler`.
- **Selection precedent:** `EVAL=HANDTUNED` and `SEARCH=GOBOT` are env/property flags read at
  construction (`SearchEngine`/`GameLoopHandler`); mirror that for the NNUE-on-GoBot-search mode.

## Development Approach

- **Regular.** Keep hand-tuned as default; add NNUE as a selectable leaf eval — do not remove it.
- The acceptance gate here is **the apparatus works** (swappable eval runs; self-play through the
  GoBot search produces a trainable dataset; a net trained on it loads and plays) — NOT a win rate.
  The "beats GoBot" outcome is the maintainer's experiment (Post-Completion).
- Preserve the GoBot search's integer-score invariants (mate/INF); NNUE scaling must not break them.

## Testing Strategy

- Unit: NNUE leaf-eval path returns finite, side-to-move-oriented, in-range integers (strictly
  `< MATE_SCORE`); the eval-mode flag selects hand-tuned vs NNUE; a GoBot search with the NNUE leaf
  runs to a fixed depth without error and is deterministic.
- Sanity: self-play-through-GoBot-search produces records with the diversity knobs honored.
- Keep the full suite green (incl. the 412/parity + hand-tuned tests — NNUE mode must not perturb
  the hand-tuned path); export format unchanged; spotless clean.
- No live-game tests here.

## Progress Tracking
- Mark `[x]` immediately. `➕` new tasks, `⚠️` blockers. Keep this file in sync.

## Implementation Steps

### Task 1: Swappable leaf eval in the GoBot search
- [x] add a leaf-eval seam to `GoBotSearcher`: an eval-mode (e.g. `LeafEval {HAND_TUNED, NNUE}`)
      with a setter/field, default HAND_TUNED (preserves current behavior + all parity tests)
- [x] implement the NNUE leaf: `NNUEModel.forward(BoardFeatureMapper.map(board, sideToMove))` →
      scale to int (`round(value * SCALE)`, `SCALE` a constant making it comparable to hand-tuned
      magnitudes), clamp to `(-MATE_SCORE, MATE_SCORE)`, oriented so positive = good for the
      side-to-move; terminal/mate handling unchanged
      (note: this search is minimax-for-`root`, not negamax — the leaf is oriented to `root` to
      match `HandTunedEval`/`leafEval`, i.e. `map(board, root)`, not the side-to-move)
- [x] unit-test: NNUE leaf returns finite in-range ints, correct side-to-move sign; hand-tuned
      path unchanged (parity tests still pass)
- [x] `./mvnw test` green

### Task 2: Wire NNUE-on-GoBot-search selection
- [ ] make the leaf-eval mode selectable via env/property (e.g. `EVAL=NNUE` with `SEARCH=GOBOT`
      selects the NNUE leaf; `EVAL=HANDTUNED` keeps hand-tuned), mirroring existing flag detection
      in `GameLoopHandler`/`SearchEngine`; load `nnue_weights.json`
- [ ] a small test that the flag selects the NNUE leaf path
- [ ] `./mvnw test` green

### Task 3: Self-play + TD-leaf targets through the GoBot search
- [ ] add a mode to `SelfPlayGenerator` (or a sibling) where **move selection uses the GoBot search**
      (`GoBotSearcher.chooseNodeBudget`/`chooseDepth` with the current NNUE net) and the **TD-leaf
      target is the GoBot search's backed-up value** (scaled back to the net's output range), blended
      by `tdLambda`; honor `EPSILON`/`EXPLORE_TURNS` for diversity
- [ ] keep the existing `SearchEngine`-based mode intact (selectable)
- [ ] unit-test the GoBot-search self-play produces well-formed records; targets in range
- [ ] `./mvnw test` green

### Task 4: Wire the pipeline
- [ ] extend `td_leaf_pass.sh` (or add `td_leaf_pass_gobot.sh`) to run the GoBot-search self-play →
      `dataset.json` → `train.py` → export, driven by env (search mode, node budget, λ, ε)
- [ ] document the exact commands for the maintainer
- [ ] `./mvnw test` green

### Task 5: Verify + notes
- [ ] full suite green (incl. parity/hand-tuned), spotless clean, export format unchanged
- [ ] record the exact maintainer commands to: train (GoBot-search self-play → net) and evaluate
      (`SEARCH=GOBOT EVAL=NNUE` vs `SEARCH=GOBOT EVAL=HANDTUNED`, and vs GoBot)

## Technical Details
- The experiment's cleanliness: identical GoBot search on both sides, only the leaf eval differs →
  any win-rate gap is pure eval quality. The `SearchAB`-style isolation, but at GoBot strength.
- NNUE scaling: pick `SCALE` so typical NNUE outputs land in the hand-tuned magnitude band; the only
  hard constraint is staying strictly within `±MATE_SCORE` and monotone-in-advantage.

## Post-Completion
*No checkboxes — maintainer-run (the actual research):*
- Iterate: GoBot-search self-play (warm-started) → TD-leaf train → export → evaluate
  `SEARCH=GOBOT EVAL=NNUE` vs `SEARCH=GOBOT EVAL=HANDTUNED` (same search, only eval differs) AND vs
  GoBot. Sweep λ / node budget; champion-vs-challenger gating.
- If the learned eval **beats hand-tuned** on the same search → superior bot, goal met; feeds Phase 3
  (`0dj.6` auto-retrain). If it does not exceed hand-tuned → the honest result (a tuned eval is hard
  to beat for this game), cleanly measured for the first time on a strong search.
