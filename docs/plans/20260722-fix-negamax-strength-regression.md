# Fix Search Strength Regression (negamax sign/frame bug) — bd nnue-trainer-raz.2.3.1

## Overview

The upgraded `SearchEngine` plays **strictly weaker** than the naive pre-#38 baseline.
Measured with the in-repo A/B harness `SearchAB` (new `SearchEngine` vs
`baseline.BaselineSearchEngine`, **same** distilled NNUE eval, equal per-move time):
result **NEW 0 – 12 OLD, 0 draws** — the new search loses every game on both colors.
Before PR #55 it was 2–8 (still won a few); PR #55 made it worse.

**Root cause (confirmed by reading the code):** PR #55 was titled "convert to Negamax"
but the code is **still classic minimax** — `alphaBeta` carries a `boolean maximizingPlayer`
with two separate max/min branches, a `maximizingPlayer ? player : getOpponent(player)`
"originalPlayer" frame, and `maximizingPlayer ? UPPER : LOWER` TT flags. The score-frame
(whose perspective a returned score is in) is inconsistent between:
- the **root** loop in `findBestActionWithTimeLimitUsingModel`, which searches each child as
  `alphaBeta(child, …, getOpponent(player), maximizingPlayer=false, …)` and then simply
  **maximizes** `if (value > bestValue)` — with no frame correction, so it is selecting the
  move that is best for the **opponent**; and
- the recursive min/max branches + the TT store/probe, whose bound flags assume the older
  frame convention.

A clean, correct **negamax** conversion removes this whole class of bug: one branch, scores
always relative to the side-to-move, `score = -alphaBeta(child, -beta, -alpha, opponent)`,
and TT bounds stored/probed in negamax convention. That is what #55 claimed but did not do.

`testTTDoesNotChangeBestMove` passes but only proves TT **determinism**, not strength — which
is why CI stayed green on a broken bot. The real acceptance gate is the SearchAB win rate.

## Context (from discovery)

- Files involved:
  - `src/main/java/com/engine/nnue_trainer/search/SearchEngine.java` (primary — `alphaBeta`,
    `quiescenceSearch`, `evaluate`, `orderActions`, `findBestActionWithTimeLimitUsingModel`)
  - `src/main/java/com/engine/nnue_trainer/search/TranspositionTable.java`,
    `search/TTEntry.java` (bound flags: EXACT / LOWER_BOUND / UPPER_BOUND)
  - `src/main/java/com/engine/nnue_trainer/search/Zobrist.java` (side-to-move already in key)
  - `src/main/java/com/engine/nnue_trainer/nnue/*` — the `evaluate(...)` leaf sign path
- Harness (the gate): `src/main/java/com/engine/nnue_trainer/train/SearchAB.java`,
  baseline `search/baseline/BaselineSearchEngine.java` (do NOT modify the baseline).
- Prior failed attempt: `docs/plans/37-fix-transposition-table-negamax.md` (intent only).
- Existing tests: `src/test/java/com/engine/nnue_trainer/search/SearchEngineTest.java`,
  `SearchEngineUpgradeTest.java`.

## Development Approach

- **Regular (code first, then verify against the strength gate).**
- The hard acceptance gate is **behavioral, not a unit test**: SearchAB must reverse 0–12
  into a clear NEW majority. Keep the fast unit tests (esp. `testTTDoesNotChangeBestMove`)
  passing as guardrails, but do not treat green unit tests as "done".
- Prefer the **full negamax rewrite** of `alphaBeta` + `quiescenceSearch` (single branch,
  drop `maximizingPlayer`) over patching the minimax frames — the half-converted state is
  the bug source. If the rewrite proves risky, the fallback is to make the existing minimax
  frames + root selection + TT flags provably consistent. Either way the gate is the same.
- Small, focused commits; rebuild and re-run SearchAB after the core change.

## Testing Strategy

- **Unit**: keep `SearchEngineTest` / `SearchEngineUpgradeTest` green; keep
  `testTTDoesNotChangeBestMove` (TT on vs off must pick the same move at the same depth).
  Add a cheap sanity test: on a trivially winning 1-ply position the engine picks the
  winning/capturing move (guards against the "plays for the opponent" inversion).
- **Behavioral gate (required)**: `SearchAB 12 500` — NEW must beat OLD by a clear margin.
- No large new test frameworks; the 12-game sim is the strength oracle, run it manually.

## Progress Tracking

- Mark completed items `[x]` immediately. `➕` for new tasks, `⚠️` for blockers.
- Keep this file in sync if scope changes.

## Implementation Steps

### Task 1: Convert alphaBeta to correct negamax
- [x] rewrite `alphaBeta` in `SearchEngine.java` to a single negamax branch: score relative
      to the side-to-move, children via `score = -alphaBeta(child, depth-1, -beta, -alpha,
      getOpponent(player), …)`, `alpha = max(alpha, score)`, cutoff on `alpha >= beta`
- [x] make the leaf return `evaluate(board, accumulator, player)` from the **side-to-move**
      perspective (adjust `evaluate` sign so a position good for `player` is positive);
      remove the `maximizingPlayer` parameter throughout the signature and callers
- [x] apply the same negamax form to `quiescenceSearch`
- [x] keep `applyAction` / `Accumulator.computeDiff` frame consistent with the new
      side-to-move convention
- [x] add a 1-ply sanity unit test (engine picks the obviously winning move)
- [x] run `./mvnw test` — must pass before next task

### Task 2: Fix TT store/probe for negamax bounds
- [x] store bounds in negamax convention: FLAG_EXACT when `alpha` improved within window,
      FLAG_LOWER_BOUND on `score >= beta` (fail-high), FLAG_UPPER_BOUND when no move raised
      alpha (fail-low); score stored relative to side-to-move
- [x] probe symmetrically (return on EXACT; LOWER with `score >= beta`; UPPER with
      `score <= alpha`); confirm `Zobrist.computeHash` keys by the side-to-move `player`
- [x] keep `testTTDoesNotChangeBestMove` passing (TT must never change the chosen move vs
      `USE_TT=false` at equal depth) — test added (was referenced but missing); TT on==off move
- [x] run `./mvnw test` — must pass before next task (70/70 green)

### Task 3: Fix the root move selection frame
- [x] in `findBestActionWithTimeLimitUsingModel`, evaluate each child as
      `score = -alphaBeta(child, depth-1, -beta, -alpha, getOpponent(player), …)` and select
      the **max** `score` — so the root chooses the move best for `player`, not the opponent
      (done in negamax rewrite: lines 651-679; child searched as `-alphaBeta(child,...,3-player,player,...)`)
- [x] keep iterative deepening, PVS/null-window, move ordering, opening book, and the
      time-limit fallback (`bestAction = actions.get(0)`) intact — all preserved
- [x] confirm both root overloads (if two remain) use the identical corrected convention —
      `findBestActionUsingModel` (560-589) and `findBestActionWithTimeLimitUsingModel` (651-679) match
- [x] run `./mvnw test` — must pass before next task (70/70 green)

### Task 4: Verify acceptance criteria (STRENGTH GATE)
- [ ] `./mvnw -q compile`
- [ ] build classpath and run the A/B:
      `java -cp "target/classes:$(./mvnw -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout | tail -1)" com.engine.nnue_trainer.train.SearchAB 12 500`
- [ ] **PASS = NEW wins a clear majority of 12 games** (reversing the current NEW 0 – 12 OLD).
      A draw-heavy or losing result is a FAIL — do not close on it.
- [ ] run the full unit suite `./mvnw test` — all green, incl. `testTTDoesNotChangeBestMove`
- [ ] run `./mvnw spotless:check` (or `spotless:apply`) — formatting clean

### Task 5: Update plan/notes
- [ ] record the final SearchAB score in this plan file
- [ ] note whether the fix was a full negamax rewrite or a minimax-frame correction

## Technical Details

- Negamax invariant: `alphaBeta(pos, player)` returns the value of `pos` **for `player`**;
  parent computes `-alphaBeta(child, opponent)`. Leaf `evaluate` must therefore be
  sign-flipped to the side-to-move, not a fixed player.
- TT stores side-to-move-relative scores; bound flag semantics must match the negamax window.
- `SearchAB` uses simplified self-play (both engines identical rules, alternating colors), so
  any win-rate gap is pure search quality — it is the correct arbiter here.

## Post-Completion

*No checkboxes — external / manual follow-up after merge:*

- After merge, the orchestrator re-runs the live parity harness `eval_java_vs_go.py 5`
  (see `.agents/skills/eval-vs-gobot/SKILL.md`) — was 0/5 on the broken search; a correct,
  stronger search should move it off zero. If SearchAB passes but GoBot is still 0/5, the
  remaining gap is eval/feature strength, not search (separate bead).
- Do not modify `BaselineSearchEngine` — it is the fixed reference for this comparison.
