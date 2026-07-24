# Self-play / challenger diversity + dedup (bd nnue-trainer-ox1)

## Overview

The deployed data-gen challenger runs `SEARCH=GOBOT`. Its play path
(`GameLoopHandler.gobotSearch` → `GoBotSearcher.chooseNodeBudget`) is fully
deterministic: a single canonical opening (`GoOpeningBook.openingBookMove`)
followed by deterministic iterative-deepening minimax. Result: near-duplicate
games (measured 47.7% unique positions, 77% of games sharing one opening, two
matchups each replaying the SAME 40-move game 50×), wasting live CPU.

Fix: add **env-gated, seeded** exploration to the GoBot play path so data-gen
games become DIVERSE but still sensible (near-best sampling, not uniform-random
flailing), and dedup positions on export from the offline generator.

**Hard constraint:** with the flag OFF the bot must still play deterministic
best-move (protects a future strong human-facing bot). Exploration is opt-in
only, default OFF for anything that isn't the data-gen challenger.

## Context (from discovery)

- `search/gobot/GoBotSearcher.java` — live entry points `choose` /
  `chooseNodeBudget` / `chooseDepth`. `atDepth` already builds all root moves
  and stores `best.alternatives` (top-4 next-best `RootMove{action,score}`,
  best-first). So `{best.action@best.score} + best.alternatives` is a ready
  top-K candidate set to sample from — no search-core surgery needed.
- `search/gobot/GoResult.java` / `RootMove.java` — carry `action`, `score`,
  `depth`, `searchComplete`, `alternatives`. A book move is uniquely
  `searchComplete && depth == 0`.
- `search/gobot/GoOpeningBook.java` — single deterministic canonical wedge
  opening; on the opening turn `chooseNodeBudget` short-circuits to it.
- `protocol/GameLoopHandler.java` — the LIVE challenger. `gobotSearch(...)`
  calls `chooseNodeBudget(gs, limit)` (or `chooseDepth`/`choose` via env). This
  is where Fix A lands.
- `train/SelfPlayGenerator.java` — offline generator. `playGoBotGames` already
  has `epsilon`/`exploreTurns`/`seed` but exploration is uniform-random and
  capped to the first 6 turns; export adds ALL records (no dedup) though it
  already computes a unique-position ratio for reporting. Fix B lands here.
  **HEADS-UP:** this file is also modified on `nnue-v2-integration`; keep the
  change minimal/localized to the exploration+dedup logic to ease that merge.
- Tests: `GoBotSelfPlayTest`, `SelfPlayGeneratorTest`, `GoOpeningBookTest`,
  `GoBotSearcherTest`, `GameLoopHandlerTest` must stay green.
- Build/test: `./mvnw test`.

## Development Approach

- Testing approach: Regular (code first, then tests) — small, focused, localized.
- One shared exploration helper, reused by BOTH the live challenger and the
  offline generator (fix once where all callers route — no duplicated sampler).
- Seeded `java.util.Random` everywhere so `same seed ⇒ same diverse games`.
- Flag OFF ⇒ byte-identical behavior to today (argmax + canonical opening).
- Keep the diff minimal and localized in `SelfPlayGenerator.java`.

## Testing Strategy

- Unit tests for the sampler (softmax weighting, seeded reproducibility, OFF =
  argmax, opening randomization only when enabled).
- A live-path test: `GameLoopHandler` with exploration OFF plays the same move
  as plain `chooseNodeBudget`; ON + fixed seed is reproducible and can differ.
- A diversity re-measure test: a small batch with exploration ON reports a
  sharply higher unique-position / unique-game ratio than the OFF baseline.
- Dedup test: export drops exact-duplicate positions and reports unique yield.
- No new dependencies; JUnit 5 like the rest of the suite.

## Progress Tracking
- Mark completed items `[x]` immediately.
- ➕ for newly discovered tasks, ⚠️ for blockers.

## Implementation Steps

### Task 1: Shared seeded exploration sampler `GoBotExploration`
- [x] add `search/gobot/GoBotExploration.java`: immutable config
  `{boolean enabled, double temperature, Random random}` plus a static
  `fromEnv(String enableKey, String tempKey, String seedKey, double defaultTemp)`
  reading `CHALLENGER_EXPLORE` (bool gate, default false),
  `CHALLENGER_EXPLORE_TEMP` (softmax temperature), `CHALLENGER_EXPLORE_SEED`
  (long; 0 ⇒ nondeterministic Random, else seeded). Support both system
  property and env (mirror `GameLoopHandler.gobotSearchFromEnv`).
- [x] `Action sampleMove(GoResult r)`: when `!enabled` return `r.action`
  (argmax — unchanged). When enabled, build candidate list
  `[(r.action, r.score)] + r.alternatives`, compute softmax weights
  `exp((score - maxScore) / tempScale)` over candidate scores, and sample one
  with `random`. Guard: null/empty alternatives ⇒ return `r.action`; a book
  result (`r.searchComplete && r.depth == 0`, null alternatives) is handled by
  the caller (opening randomizer), so `sampleMove` just returns `r.action`
  there. temperature ≤ 0 ⇒ argmax (deterministic).
- [x] `Action sampleOpening(java.util.List<Action> legal)`: when enabled, pick a
  uniform-random legal action (on the opening turn the only legal actions are
  sensible near-base placements, so this is a diverse-but-legal opening);
  when disabled, return null so the caller keeps the canonical book move.
- [x] write tests `GoBotExplorationTest`: OFF ⇒ `sampleMove` == `r.action`;
  ON + seed reproducible (same seed ⇒ same pick); temperature 0 ⇒ argmax; high
  temperature ⇒ can pick a non-best alternative; empty/null alternatives safe;
  `fromEnv` parses gate/temp/seed and defaults to disabled.
- [x] run tests — must pass before next task.

### Task 2: Fix A — gate exploration into the LIVE challenger
- [x] in `GameLoopHandler`: build a `GoBotExploration` (per-instance, read at
  construction like `useGobotSearch`) from `CHALLENGER_EXPLORE*`.
- [x] in `gobotSearch(...)`: after obtaining `r`, if exploration enabled and `r`
  is a book move (`searchComplete && depth == 0`), replace the chosen action
  with `sampleOpening(gs.legalActions())`; otherwise replace with
  `sampleMove(r)`. Rebuild the returned `SearchResult` around the chosen
  action (keep score/depth/nodes diagnostics from `r`). When disabled, the code
  path and returned action are unchanged (still `r.action`).
- [x] keep the existing `GOBOT_FIXED_DEPTH` / `GOBOT_NODE_LIMIT` /
  `GOBOT_TIME_MODE` env handling intact.
- [x] write tests `ChallengerExploreTest`: with exploration OFF the adapted
  action equals plain `chooseNodeBudget` best action for a mid-game position;
  with ON + fixed seed the selection is reproducible across two constructions;
  opening position with ON yields a legal placement (may differ from the
  canonical book move).
- [x] run tests — must pass before next task.

### Task 3: Fix B — offline SelfPlayGenerator sampling across all turns + dedup
- [x] add `Config` fields `exploreTemperature` (default 0.0 = keep existing
  uniform-random behavior) and reuse `epsilon`/`exploreTurns`; add env
  `EXPLORE_TEMP` in `main`. Keep changes localized (v2-branch merge).
- [x] in `playGoBotGames`: construct a `GoBotExploration` from
  `{epsilon>0 || temp>0, exploreTemperature, seed}`. Allow exploration across
  ALL turns when `exploreTurns` covers them (already env-tunable); when
  `exploreTemperature > 0` choose the move via `sampleMove(r)` (near-best
  softmax) instead of uniform-random `legal.get(random.nextInt(...))`. Keep the
  epsilon uniform path as the fallback when temp == 0 so existing behavior/tests
  are unchanged by default.
- [x] DEDUP on export: track a `Set<Integer>` of feature-hashes and skip adding
  a `TrainingRecord` whose position hash was already emitted; report
  kept-vs-total (unique-position yield) alongside the existing
  `distinctGameRatio`. Add a `dedup` Config flag (default true) so the reporting
  path stays observable.
- [x] extend `GenerationResult` with `int totalPositionsSeen` (pre-dedup) so the
  unique yield is reportable/testable without changing existing fields' meaning.
- [x] write tests: sampling path produces finite in-range targets (like
  `GoBotSelfPlayTest`); dedup removes exact-duplicate positions and
  `dataset.size() == uniquePositions`; a seeded run is reproducible.
- [x] run tests — must pass before next task.

### Task 4: Fix C — diversity re-measure test
- [x] add `SelfPlayDiversityTest`: generate a small GoBot batch with exploration
  OFF (baseline) and ON (seeded temperature), assert the ON unique-position
  ratio (and distinct-game count) is materially higher than OFF, and that the
  OFF batch is deterministic/near-duplicate (reproduces the reported low
  baseline). Keep it fast (few games, shallow depth).
- [x] run tests — must pass before next task.

### Task 5: Verify acceptance criteria
- [ ] flag OFF still plays deterministic best-move (live + offline).
- [ ] flag ON produces sharply higher unique-game / unique-position ratios.
- [ ] existing `SelfPlayGeneratorTest`, `GoBotSelfPlayTest`, `GoOpeningBookTest`,
  `GoBotSearcherTest`, `GameLoopHandlerTest` all green.
- [ ] run full `./mvnw test`.
- [ ] run the formatter/linter if the build enforces one (spotless) — fix issues.

## Technical Details

- Candidate set for `sampleMove`: `best` (`r.action`,`r.score`) plus up to 4
  `alternatives`. Alternative scores are scout/bound approximations — acceptable
  for exploration; sampling stays "near-best" because the list is best-first and
  temperature is small by default.
- Softmax: `w_i = exp((score_i - maxScore) / (NNUE_SCALE * temperature))` (scale
  the raw integer scores so temperature is O(1)); normalize; inverse-CDF sample.
- Opening randomization: only fires when the result is a book move AND
  exploration is enabled; a uniform pick over `legalActions()` on the opening
  turn is a legal near-base placement by the game's growth rules.
- Reproducibility: one seeded `Random` per generator/handler instance; same seed
  ⇒ identical (diverse) sequence.

## Post-Completion

**Manual verification:**
- Run the data-gen challenger with `CHALLENGER_EXPLORE=true`
  `CHALLENGER_EXPLORE_TEMP=0.6` `CHALLENGER_EXPLORE_SEED=1` against a live/offline
  batch and confirm the games DB unique-position ratio jumps well above the
  ~48% baseline.
- Confirm production default (no `CHALLENGER_EXPLORE`) still plays the strongest
  deterministic line.

**External system updates:**
- `nnue-v2-integration` will need conflict resolution on `SelfPlayGenerator.java`
  — the change is deliberately localized to the exploration+dedup logic.
</content>
</invoke>
