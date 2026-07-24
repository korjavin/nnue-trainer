# Phase 3: Automated continuous retraining loop (bd nnue-trainer-0dj.6)

## Overview

The end-goal step 3: the bot **retrains itself automatically** and only ever gets stronger. Build a
continuous loop — self-play → train → evaluate → **promote only if strictly better** — with
champion-vs-challenger gating so it never ships a regression, logging every generation's win rate.

Context that shapes this: Phase 2 showed a single learned-NNUE pass loses 0-6 to the hand-tuned
eval on the strong GoBot search (hand-tuned = 6-0 vs GoBot). So the loop's realistic value is
**(a)** the safe apparatus (never promote a worse net), and **(b)** giving the "can NNUE exceed
hand-tuned" question its best *automated, many-iteration* shot. The **hand-tuned clone is the
champion bar to beat** — the loop must never deploy something weaker than it.

**Scope (ralphex-buildable):** the offline gating match, the loop driver with champion/challenger
promotion, safety gates, logging, docs. The **long multi-generation run is maintainer-driven** (I
kick it off and watch); ralphex builds the machinery + a short smoke that proves one generation
cycles correctly.

## Context (from discovery)

- **`train/PeriodicRetrainer.java`** — existing retrain infra: `CandidateEvaluator`, `retrainOnce`,
  `runGauntlet`, a `ScheduledExecutorService`. Reuse/extend rather than reinvent.
- **`train/SearchAB.java`** — a Java-vs-Java match harness (currently new-vs-old search, same model).
  Mirror it for **net-vs-net**: same GoBot search both sides, `EVAL=NNUE` leaf, champion weights vs
  challenger weights.
- **`nnue/NNUEModel.load(Path)`** — loads a net from an arbitrary file (champion + challenger from
  different paths). `createDefault()` loads the classpath `nnue_weights.json`.
- **`search/gobot/GoBotSearcher`** — the strong search; `chooseNodeBudget`/`chooseDepth` are
  deterministic. `search/eval/HandTunedEval` is the champion bar. `EVAL=NNUE`/`EVAL=HANDTUNED`,
  `SEARCH=GOBOT` selection already wired (Phases 1-2).
- **`td_leaf_pass_gobot.sh`** — one GoBot-search self-play → TD-leaf → train → export pass (env:
  `NUM_GAMES`, `GOBOT_NODE_LIMIT`/`GOBOT_FIXED_DEPTH`, `TD_LAMBDA`, `EPSILON`, `EXPLORE_TURNS`, `SEED`).
- **`eval_java_vs_go.py`** — live vs GoBot (slow, esp. NNUE ~13.5s/move) — for periodic sanity only,
  NOT the per-generation gate.
- Weights live at `src/main/resources/nnue_weights.json` (baked into the deployed jar).

## Development Approach

- **Regular.** Reuse `PeriodicRetrainer`/`SearchAB` seams; don't reinvent.
- **The per-generation gate is an OFFLINE net-vs-net match** (fast, deterministic), NOT live GoBot
  games. Live vs-GoBot is a periodic sanity check only.
- **Never promote a regression.** Champion starts as the current net; a challenger is promoted only
  if it beats the champion by a clear margin over the match. Also gate against the **hand-tuned bar**
  so the loop never deploys something weaker than the 6-0 clone.
- Acceptance here = the loop cycles one generation correctly with safe gating (smoke), fully tested.
  The many-generation strength run is maintainer-driven (Post-Completion).

## Testing Strategy

- Unit: net-vs-net match returns a W-L for two given weight files (deterministic under fixed
  seeds/alternating colors + node budget); promotion decision (promote iff challenger margin ≥
  threshold); champion history append; "never promote below hand-tuned bar" guard.
- Smoke: one loop generation (tiny self-play → train → match → promote-or-keep) runs end-to-end and
  leaves the champion unchanged if the challenger isn't better.
- Keep the full suite green; hand-tuned/parity paths unperturbed; spotless clean.
- No long training / no live-game tests in CI.

## Progress Tracking
- Mark `[x]` immediately. `➕` new tasks, `⚠️` blockers. Keep this file in sync.

## Implementation Steps

### Task 1: Offline net-vs-net match harness
- [x] add a `GauntletMatch` (mirror `SearchAB`): GoBot search on both sides (`EVAL=NNUE`),
      load champion + challenger via `NNUEModel.load(pathA/pathB)`, play N games alternating colors
      with a fixed node budget + seeds, return `{wins, losses, draws}`; also support a side being
      `HandTunedEval` (challenger-vs-hand-tuned-bar)
- [x] unit-test: identical weights → ~even; deterministic given seeds
- [x] `./mvnw test` green

### Task 2: Promotion decision + champion management
- [x] a champion store (e.g. `champions/` dir + a `champion.json` pointer / history log); current
      champion = the net currently in `nnue_weights.json`
- [x] promotion rule: promote challenger iff it beats champion by margin ≥ `PROMOTE_MARGIN` (env,
      default e.g. wins − losses ≥ 2 over the match) AND does not lose to the hand-tuned bar by more
      than the champion does (never regress below the 6-0 clone's level)
- [x] on promote: copy challenger → `nnue_weights.json`, append to champion history with its W-L;
      on reject: keep champion, log the rejected challenger's W-L
- [x] unit-test the decision + history append
- [x] `./mvnw test` green

### Task 3: The loop driver
- [x] `td_retrain_loop.sh` (or extend `PeriodicRetrainer`): for `GENERATIONS` (env), each gen:
      self-play with the current champion via `td_leaf_pass_gobot.sh` knobs (diverse: `EPSILON`,
      `EXPLORE_TURNS`) → train challenger → `GauntletMatch` challenger-vs-champion → promote-or-keep
      → append a per-generation line (gen, val MSE, W-L vs champion, promoted?) to a run log
- [x] a hard budget guard (max generations / wall-clock) so it can't run away
- [x] document the exact maintainer command + env knobs
- [x] `./mvnw test` green (logic unit-tested; the script itself smoke-run once)

### Task 4: Safety + sanity
- [x] never mutate `nnue_weights.json` except via a passed promotion (atomic: write temp, then move)
      — `ChampionStore.atomicReplace` runs only inside `promote()`; training writes the challenger to
      `OUT_PATH=$CHALLENGER` (inherited by `train.py`), never the champion
- [x] optional periodic live sanity: every K generations, run `eval_java_vs_go.py` vs GoBot and log
      (off by default — `LIVE_SANITY_EVERY=0`); champion-vs-hand-tuned offline check each gen instead
- [x] a small integration smoke: 1 generation, tiny settings, asserts champion only changes on a
      real improvement and the run log is written (`RetrainSmokeTest`)
- [x] `./mvnw test` green; `./mvnw spotless:check` clean

### Task 5: Verify + notes
- [ ] full suite green, spotless clean, weights untouched unless a genuine promotion in a smoke
- [ ] record the exact maintainer command to run the loop for N generations + how to read the log

## Technical Details
- Gating on an **offline net-vs-net match** (deterministic, fast) is what makes continuous retrain
  practical — live GoBot games at ~13.5s/move (NNUE) are far too slow per generation.
- Champion-vs-challenger + never-below-hand-tuned-bar guarantees monotonic non-regression: the
  deployed net is always ≥ the best seen, and never worse than the shipped 6-0 clone.

## Post-Completion
*No checkboxes — maintainer-run (the long experiment):*
- Kick off the loop for many generations (warm-started, diverse self-play) and watch the log: does
  a promoted challenger ever beat the hand-tuned bar? Given Phase 2's 0-6, this is a long shot per
  generation, but the loop is the only way to give it a fair, safe, many-iteration try — and it can
  never ship a regression. If a net ever clears the hand-tuned bar → promote to `nnue_weights.json`
  via PR and it deploys. If not → the hand-tuned clone remains the shipped bot (already 6-0).
