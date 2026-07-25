# NNUE v3.0 GATE — feature preview: mine absolute (r,c,state) features, rank by eval-discrimination, visualize

## Overview

Owner gate for bead `nnue-trainer-d4a.6.5`. Before ANY hard NNUE v3 investment (model, ridge
regression, capacity test, gauntlet), produce a **visualization of the candidate v3 features** so
the owner can SEE and approve what we are about to build.

This bead is **mining + ranking + HTML only**. Explicitly **NO model, NO regression, NO training,
NO gauntlet** — those are beads d4a.6.1/6.2 and are gated on this preview.

What v3 features are (from `docs/nnue-v3-design.md`, if present on this branch, else this spec):

- A feature is an **absolute `(row, col, cell-state)`** on a **fixed 12x12** board — whole-board and
  position-aware, NOT a 5x5 local window (5x5-local was v2, and v2 lost the gauntlet 0-24).
- Cell state is **STM-perspective-normalized** (self/opp), reusing the existing v2 symbol mapping.
- ~144 cells x 8 on-board states = ~1152 candidate features.
- Features are ranked by **eval-discrimination**, NOT by frequency. Frequency-ranking is the exact
  v2 bug: it promotes the most *common* shapes, which are trivial (lone stone in a corner,
  near-empty windows). Common != informative.

Benefit: a cheap, fast, inspectable go/no-go. If the top eval-discriminative position-aware
features look meaningful to the owner, d4a.6.1 (regression + capacity R2) proceeds; if they look
like noise, we change the feature design before spending on a model.

Integrates with the existing games.db replay pipeline built in bead d4a.5.1
(`GamesDbPatternMiner`), which already replays real games through the real engine.

## Context (from discovery)

Branch: `v3-feature-preview`, based off `games-db-features-viz`. **Merge target is this v2/v3
branch stack, NOT master.**

Files/components involved:

- `src/main/java/com/engine/nnue_trainer/train/GamesDbPatternMiner.java` — existing games.db
  replay + 5x5 mining CLI (bead d4a.5.1). Owns the replay logic to be reused: `replay(rows, cols,
  turns)`, `initialBoard(rows, cols)`, `parseAction(move)`, the `Snapshot`/`Replay` holders, the
  skip-reason bookkeeping, and the `MOVES_LEFT = 3` fresh-turn assumption.
- `src/main/java/com/engine/nnue_trainer/v2/PatternContract.java` — symbol codes and mapping.
  `public static int getSymbol(Cell cell, int stmOwner)` returns the perspective-normalized symbol;
  constants `EMPTY=0, NEUTRAL=1, BASE_SELF=2, BASE_OPPONENT=3, NORMAL_SELF=4, NORMAL_OPPONENT=5,
  FORTIFIED_SELF=6, FORTIFIED_OPPONENT=7, OUT_OF_BOUNDS=8`. `OUT_OF_BOUNDS` cannot occur for
  on-board cells, so v3 uses states 0..7 only.
- `src/main/java/com/engine/nnue_trainer/search/eval/HandTunedEval.java` — the ported GoBot static
  eval. `public static int staticEval(Board board, int player, int movesLeft, boolean[]
  neutralUsed)`, STM-relative (positive = good for `player`).
- `src/main/java/com/engine/nnue_trainer/search/SearchEngine.java` — `applyAction` used by replay
  so virus-conversion/connectivity rules apply.
- `docs/games-db-features.html` — the established self-contained viz pattern from bead d4a.5.2:
  inlined data as `<script>const DATA = {...}`, inline CSS/JS, no external assets, theme-aware via
  CSS custom properties + `prefers-color-scheme`, per-cell-code color variables `--c0..--c8`.
- `games_db_pattern_stats.json` — precedent for committing real mined stats to the repo.
- Data: `/home/iv/games.db` (sqlite, table `games`, columns `rows, cols, result, termination,
  pgn_content`). `pgn_content` is JSON turns.

Dependencies: Java 17 + Maven (`./mvnw`), Jackson (already used), sqlite-jdbc (already used).
**No new dependencies.**

## Development Approach

- **Testing approach**: Regular (code first, then tests).
- Complete each task fully before moving to the next.
- Make small, focused changes. Keep the diff minimal — reuse before writing new code.
- **CRITICAL: every task MUST include new/updated tests** for code changes in that task.
  - tests are a required part of the checklist, not optional
  - cover both success and edge/error scenarios
  - keep them focused: the shared-replay refactor needs a behavior-preservation check, the
    discrimination math needs an arithmetic check. Do not write a test per getter.
- **CRITICAL: all tests must pass before starting the next task** — no exceptions.
- **CRITICAL: update this plan file when scope changes during implementation.**
- Do NOT touch NNUE v1 or v2 model/eval code paths. The only permitted change to existing code is
  the behavior-preserving extraction of the shared replay helper out of `GamesDbPatternMiner`.
- Backward compatibility: `GamesDbPatternMiner`'s CLI and its `games_db_pattern_stats.json` output
  must remain byte-for-byte equivalent after the refactor.

## Testing Strategy

- **Unit tests**: JUnit, under `src/test/java/com/engine/nnue_trainer/`, matching existing project
  conventions.
- **E2E tests**: project has no UI e2e harness — not applicable.
- The mined `nnue_v3_feature_stats.json` is validated by assertion in the acceptance task (sane
  counts, ranking actually ordered by discrimination, support floor respected), not by a fixture.

## Progress Tracking

- Mark completed items with `[x]` immediately when done
- Add newly discovered tasks with ➕ prefix
- Document issues/blockers with ⚠️ prefix
- Update plan if implementation deviates from original scope
- Keep plan in sync with actual work done

## What Goes Where

- **Implementation Steps** (`[ ]` checkboxes): code, tests, mined data artifact, HTML viz.
- **Post-Completion** (no checkboxes): owner eyeball review of the viz, GitHub Pages/Artifact
  publication, the go/no-go decision on d4a.6.1.

## Implementation Steps

### Task 1: Extract the games.db replay path into a reusable helper

- [x] create `src/main/java/com/engine/nnue_trainer/train/GamesDbReplay.java` holding the replay
      logic currently private to `GamesDbPatternMiner`: the public `Snapshot` holder (board, stm,
      neutralUsed), a `Replay` result (snapshots + nullable skipReason), `replay(int rows, int
      cols, JsonNode turns)`, `initialBoard(int rows, int cols)`, and `parseAction(JsonNode move)`
- [x] move (do not copy) that code out of `GamesDbPatternMiner` and have it delegate to
      `GamesDbReplay`, leaving its CLI behavior, skip reasons, JSON output and console report
      completely unchanged
- [x] keep the `MOVES_LEFT = 3` fresh-turn assumption documented on the helper so both miners share
      one definition of "position"
- [x] write a test asserting the helper replays a small hand-written 2-player PGN into the expected
      number of snapshots, with the expected STM per snapshot and a non-initial board at the end
- [x] write tests for the skip paths: unparseable/missing `player`, a 3-player game (`multiplayer`),
      and an action that throws (`replay_error`)
- [x] run tests - must pass before next task

### Task 2: Mine absolute (row, col, state) feature stats with eval-discrimination

- [x] create `src/main/java/com/engine/nnue_trainer/train/V3FeatureMiner.java` with a `main` that
      reads games.db via sqlite-jdbc exactly as `GamesDbPatternMiner` does, replaying via
      `GamesDbReplay`
- [x] filter to **12x12 games only** (v3 fixes the board size); count rejects as a
      `wrong_board_size` skip reason in meta alongside the existing skip reasons
- [x] per position (board before a turn, STM = that turn's player): compute
      `HandTunedEval.staticEval(board, stm, 3, neutralUsed)` and accumulate it into
      `baselineEvalSum`/`positions`
- [x] per position, for each of the 144 cells, resolve the state via
      `PatternContract.getSymbol(board.getCell(r, c), stm)` and accumulate into the
      `(row, col, state)` feature: `support++` (positions in which the feature is active) and
      `evalSum += posEval`. Exactly one state is active per cell per position, so total support
      across the 8 states of any cell equals `positions`
- [x] compute `baseline_mean_eval = baselineEvalSum / positions`, per feature
      `mean_eval = evalSum / support` and `discrimination = |mean_eval - baseline_mean_eval|`
- [x] rank by `discrimination` descending (ties broken deterministically by row, col, state) among
      features meeting the support floor; features below the floor keep their stats but get
      `rank = -1` and are flagged `below_support_floor`
- [x] support floor: CLI flag `--min-support N`; **default `max(30, positions / 100)`** (i.e. ~1% of
      replayed positions, never below 30) — recorded in meta as `support_floor` together with
      `support_floor_source` (`default` or `flag`)
- [x] emit `nnue_v3_feature_stats.json` via Jackson (pretty-printed, deterministic ordering) with
      `meta` (games_total, games_used, games_skipped, skip_reasons, board_filter `12x12`, positions,
      baseline_mean_eval, support_floor, moves_left_assumption, feature_count) and `features` (row,
      col, state, state_name, support, mean_eval, discrimination, rank)
- [x] print a headline console report (games used/skipped, positions, baseline mean eval, support
      floor, how many features cleared it, top 10 by discrimination)
- [x] write a test for the accumulation/discrimination math on a tiny synthetic set of positions
      with known evals (assert mean_eval, discrimination, and the support-floor exclusion)
- [x] write a test asserting ranking is by discrimination and NOT by support: a low-support (but
      above floor) high-discrimination feature must outrank a high-support low-discrimination one
- [x] run tests - must pass before next task

### Task 3: Run the miner on real games.db and commit the stats artifact

- [x] build with `./mvnw -q compile` (or `test-compile`) and run `V3FeatureMiner` against the real
      `/home/iv/games.db`, writing `nnue_v3_feature_stats.json` at the repo root (same convention as
      `games_db_pattern_stats.json`)
- [x] sanity-check the output: non-zero `games_used`, non-zero `positions`, `board_filter` is 12x12,
      per-cell support across states sums to `positions`, and the top-ranked features have
      discrimination strictly greater than the bottom-ranked ones
- [x] **commit `nnue_v3_feature_stats.json`** — the real mined data is the point of this gate
- [x] games.db present and yielded 12x12 games — no fabrication needed
- [x] run tests - must pass before next task

Mined result (real `/home/iv/games.db`, 2026-07-25): 273 games total, 213 used, 60 skipped
(`disconnect=22, wrong_board_size=13, illegal_move=7, multiplayer=7, no_pgn=7, replay_error=4`),
3282 positions, `baseline_mean_eval = -3605.17`, default support floor 32, 444 observed features,
275 above floor. Top feature: `(6,5) NORMAL_SELF` discrim 23942.87 (support 68) — centre-ish
own-stone cells dominate, and the ranking is clearly not frequency-driven (support 44–161 across
the top 10). Sanity assertions verified: all 144 cells sum their per-state support to exactly 3282,
ranked list ordered by discrimination desc with (row, col, state) tie-break, every ranked feature
at/above the floor, top discrimination > bottom.

⚠️ Two PRE-EXISTING test failures on this branch, unrelated to this bead and out of scope (the plan
forbids touching v1/v2 model/eval code): `NNUEv2AccumulatorTest.testParityAgainstPythonFixture` and
`PatternDictionaryTest.testKnownSignatureMapsToId` — both are v2 fixture-drift against committed
`python/v2/nnue_v2_dictionary.json`. All v3/replay tests (`V3FeatureMinerTest`, `GamesDbReplayTest`,
`GamesDbPatternMinerTest`) pass.

### Task 4: Self-contained HTML preview at docs/nnue-v3-feature-preview.html

- [x] create `docs/nnue-v3-feature-preview.html` following the structure and design language of
      `docs/games-db-features.html`: single file, data inlined as `<script>const DATA = {...}` from
      `nnue_v3_feature_stats.json`, inline CSS/JS, **no external assets/CDN/fonts**, theme-aware via
      `prefers-color-scheme`, responsive, reusing the `--c0..--c7` cell-code colors
- [x] headline stats band: games used/skipped, positions, baseline mean eval, support floor,
      features above floor, distinct features
- [x] **12x12 board heatmaps**: one board per cell-state (self/opp/base/fortified/neutral/empty),
      each cell shaded by that `(r,c,state)` feature's signed deviation `mean_eval -
      baseline_mean_eval` (diverging scale: positive = good-for-STM, negative = bad-for-STM),
      intensity by magnitude, with below-floor cells visually muted; hover/title shows support,
      mean_eval, discrimination, rank
- [x] **ranked table** of the top features (row, col, state, discrimination, mean_eval, support,
      rank), sortable by clicking a column header, defaulting to discrimination descending
- [x] legend for the cell-state colors and the diverging eval scale, plus a short "how to read this"
      note stating explicitly that ranking is by **eval-discrimination, not frequency** (frequency
      ranking was the v2 bug), that features are absolute board positions from the side-to-move's
      perspective, and that this is a pre-model preview with no trained weights in it
- [x] verify the page opens standalone with no network requests and renders in both light and dark
      color schemes (no-network verified by grep + test; DOM verified by executing the page under
      jsdom — 6 tiles, 8 boards x 144 cells, 444 table rows, sorting works. ⚠️ no browser is
      installed on this host, so the light/dark **visual** render was not screenshotted; the theme
      mechanism is the same `prefers-color-scheme` + `[data-theme]` CSS-variable block already
      shipped in `docs/games-db-features.html`)
- [x] write a test asserting the committed HTML is self-contained and in sync with the stats: no
      `http://`/`https://`/`src=`-to-remote references, and the inlined `DATA` block's headline
      numbers (positions, feature count) match `nnue_v3_feature_stats.json`
- [x] run tests - must pass before next task

### Task 5: Verify acceptance criteria

- [ ] verify the bead's steps are all covered: replay reuse, 12x12 static eval per position,
      absolute (r,c,state) extraction, support + mean_eval + discrimination accumulation,
      discrimination ranking with support floor, HTML board heatmap + ranked list
- [ ] verify NO model, regression, training or gauntlet code was added (this is the gate, not 6.1)
- [ ] verify `GamesDbPatternMiner` is behaviorally unchanged by the Task 1 refactor (its CLI still
      runs and produces the same `games_db_pattern_stats.json` shape)
- [ ] verify edge cases: zero 12x12 games, support floor larger than any feature's support, a
      feature active in every position (discrimination ~0)
- [ ] run the full test suite (`./mvnw test`) — must pass
- [ ] run the project's formatter/linter (spotless/checkstyle as configured) — all issues fixed

### Task 6: [Final] Update documentation

- [ ] add a short "v3.0 gate" section to `docs/nnue-v3-design.md` (create the section only if the
      file exists on this branch) pointing at `docs/nnue-v3-feature-preview.html` and
      `nnue_v3_feature_stats.json`, and stating the gate is owner-eyeball approval before d4a.6.1
- [ ] document how to regenerate: the `V3FeatureMiner` command line, the `--min-support` flag and
      its default, and how the HTML's inlined `DATA` is refreshed from the JSON

## Technical Details

**Position definition** (shared with d4a.5.1): a position is the board state **before** a turn; STM
is that turn's player; eval is STM-relative; `movesLeft = 3` (fresh turn — a fixed assumption, the
real per-ply movesLeft is not reconstructed from the PGN).

**Feature key**: `(row, col, state)` with `row, col ∈ [0,12)` and `state ∈ [0,8)`
(`PatternContract` codes 0..7; `OUT_OF_BOUNDS` is unreachable for on-board cells). Dense
`row * 12 * 8 + col * 8 + state` indexing is sufficient — 1152 entries, no hashing needed.

**Discrimination**: `discrimination(f) = |mean_eval(positions where f active) − baseline_mean_eval|`
gated by a minimum-support floor. Support is a *floor*, never a ranking key.

**Output JSON** (`nnue_v3_feature_stats.json`):

```
{
  "meta": {
    "games_total": N, "games_used": N, "games_skipped": N,
    "skip_reasons": {"wrong_board_size": N, "no_pgn": N, ...},
    "board_filter": "12x12",
    "positions": N,
    "baseline_mean_eval": F,
    "support_floor": N, "support_floor_source": "default"|"flag",
    "moves_left_assumption": 3,
    "feature_count": N
  },
  "features": [
    {"row": R, "col": C, "state": S, "state_name": "NORMAL_SELF",
     "support": N, "mean_eval": F, "discrimination": F, "rank": N}
  ]
}
```

**CLI**: `V3FeatureMiner [db-path] [out-path] [--min-support N]`, defaulting to
`/home/iv/games.db` and `nnue_v3_feature_stats.json` (mirroring `GamesDbPatternMiner`'s
positional-args convention).

## Post-Completion

*Items requiring manual intervention or external systems — no checkboxes, informational only*

**Manual verification**:

- Owner eyeballs `docs/nnue-v3-feature-preview.html` and judges whether the top eval-discriminative
  position-aware features look meaningful. This is the gate.

**External system updates**:

- Publish the page (GitHub Pages under `docs/` and/or a shared Artifact link) for owner review.
- Gate outcome unblocks bead `nnue-trainer-d4a.6.1` (mine + ridge-distill + capacity R2), or sends
  the feature design back for revision (e.g. pairwise/region features) before any model work.
