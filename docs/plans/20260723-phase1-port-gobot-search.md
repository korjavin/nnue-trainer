# Phase 1: Port GoBot's search stack to Java → parity floor (bd nnue-trainer-0dj.2)

## Overview

The decisive Phase-0 experiment proved it: our negamax search + GoBot's **exact** eval
(`EVAL=HANDTUNED`) still loses **0–5** to GoBot. The eval is not the gap — GoBot's **search**
is. This phase ports GoBot's search (`../virusgame/backend/search/search.go` +
`opening_book.go`) faithfully to Java as a selectable engine (`SEARCH=GOBOT`). Combined with the
already-ported `EVAL=HANDTUNED` (Phase 0), that is a **GoBot clone by construction** → the
guaranteed **parity floor** the epic (`0dj`) is built on.

Key facts from discovery:
- GoBot's search is **standard**: iterative-deepening `minimax` (alpha-beta + PVS null-window
  re-search) with a transposition table, `maxN` for >2 players, and an opening book. **No
  quiescence** — it searches to a fixed depth then calls the static eval. (Our `SearchEngine`
  *has* quiescence GoBot lacks — a likely source of the behavioral gap.)
- **No SPSA-tuned search params** — the SPSA tuning is eval-only, already ported in Phase 0.
- `ChooseDepth(ctx, state, depth)` is **deterministic and fully-completed** → a clean move-parity
  oracle (exactly how `staticevalgen` gave Phase 0 its integer-parity oracle).

**Scope (ralphex-runnable):** port the search + opening book as a new `SEARCH=GOBOT` engine, and
prove **fixed-depth move parity** against a generated GoBot oracle. Do NOT modify the existing
`SearchEngine`/NNUE path — add alongside, selectable (mirror Phase 0's `EVAL=HANDTUNED`). The
**live-game parity vs GoBot** (the epic's real floor check) is maintainer-run (needs live games).

## Context (from discovery)

- **Source to port** (`../virusgame/backend/search/`):
  - `search.go` (531 lines): `const`s (maxDepth, TT flags), `Result`, `RootMove`,
    `topAlternatives`, `tableEntry`, `searcher`, `newSearcher`, `ChooseDepth` (deterministic
    fixed-depth — the oracle entry), `Choose` (iterative deepening + time), `chooseNodeBudget`
    (node-limited), `atDepth`, `minimax` (AB + PVS: full window on first move, `alpha,alpha+1`
    null window then re-search; TT probe/store with fail-soft flags), `maxN` (>2 players).
  - `opening_book.go` (141 lines): `openingBookMove` (canonical "thick" first-turn placement on
    a fresh opening turn), `openingBookResult` (wraps it as a depth-0 Result). Entry points call
    it first (search.go:103,154).
- **Already ported (Phase 0):** `search/eval/HandTunedEval.java` (GoBot's exact static eval,
  integer-parity). The ported search must evaluate leaves via `HandTunedEval` (with the same
  non-board state — movesLeft/neutralUsed — Phase 0 already wired).
- **Java state/rules:** `board/Board.java`, `MoveGenerator.java`, `SearchEngine.applyAction`,
  `nnue/BoardFeatureMapper` conventions. Our game is 1v1 (players 1,2) → the live path is
  book → `minimax`; `maxN` is only exercised at >2 players (port for fidelity, low priority).
- **Verification oracle:** build a small Go tool in `../virusgame` mirroring
  `arena/cmd/staticevalgen` — call `search.ChooseDepth(state, D)` per position and emit
  `{board, player, depth, action, score}` JSONL. That is the deterministic parity fixture.
- **Existing search to keep untouched:** `search/SearchEngine.java` (our negamax + quiescence)
  and `search/OpeningBook.java` stay; the port is a separate selectable engine.

## Development Approach

- **Regular** (port faithfully, then prove fixed-depth move parity against the oracle).
- **Faithful translation, not improvement**: keep GoBot's structure/order so move choices match.
  No quiescence (GoBot has none). Integer scores (from HandTunedEval).
- The acceptance gate is **deterministic move parity at fixed depth** on the fixture — a
  fully-completed fixed-depth search is deterministic, so the ported `ChooseDepth` must pick the
  **same action** (and same score) as GoBot on every fixture position.
- Do NOT touch the NNUE/`SearchEngine` path. Do NOT run live GoBot games (maintainer does).

## Testing Strategy

- **Move-parity unit test (required):** load a checked-in oracle JSONL (from the new Go tool,
  a few hundred diverse positions at a couple of fixed depths) and assert the Java
  `ChooseDepth(board, player, depth)` returns the **same action** as `record.action` (and same
  integer `score`) for every record. This is the whole gate.
- Component tests where cheap: TT probe/store round-trip; PVS re-search path; opening-book
  trigger + void condition.
- Keep the full existing suite green; export/format unchanged; NNUE path untouched.
- No live-game tests here.

## Progress Tracking
- Mark `[x]` immediately. `➕` new tasks, `⚠️` blockers. Keep this file in sync.

## Implementation Steps

### Task 1: Generate the GoBot search parity oracle
- [x] add a Go tool in `../virusgame` (mirror `arena/cmd/staticevalgen`) that, per position, calls
      `search.ChooseDepth(ctx, state, depth)` for a couple of small fixed depths (e.g. 3 and 5)
      and emits `{board, player, depth, action, score}` JSONL (action encoded to match our
      `Action` — row/col/type; document the exact encoding)
      — `backend/arena/cmd/searchparitygen/main.go`; action = `{"type":"MOVE","target":{row,col}}`
      or `{"type":"PLACE_NEUTRALS","pos1":{row,col},"pos2":{row,col}}` (maps to Java
      `MoveAction`/`PlaceNeutralsAction`). Also emits `movesLeft`/`neutralUsed` hidden state.
- [x] generate a few hundred diverse positions (reuse clean go-vs-go games / self-play snapshots)
      — diverse roster self-play (budget/tournament/greedy/base/mobility agents), sampled every
      3rd ply; per-position `-timeout` gates selection only (kept records are fully-completed
      deterministic searches → reproducible in Java with no timeout).
- [x] check a trimmed fixture into `src/test/resources/gobot_search_parity.jsonl`
      — 412 records: depths {3:236, 5:176}, players {1,2}, MOVE 391 + PLACE_NEUTRALS 21, 179 scores.
- [x] document exactly what State the oracle builds (movesLeft/neutralUsed/currentPlayer) so the
      Java port reproduces it (same lesson as Phase 0's hidden state)
      — `src/test/resources/gobot_search_parity.README.md` (schema, action encoding, hidden state,
      note that `ChooseDepth` skips the opening book so records are pure search).

### Task 2: Port the transposition table + searcher scaffolding
- [x] port `searcher`, `tableEntry`, TT flags/const, `Result`, `RootMove`, `newSearcher` to Java
      (e.g. `search/gobot/GoBotSearch.java` + helpers), keyed by the same Zobrist/position hash
      — `search/gobot/`: `GoBotSearcher` (searcher + flags + newSearcher + probe/store + running),
      `TableEntry`, `GoResult`, `RootMove`, `GoState` (fields + accessors + `hash()` = byte-exact
      port of GoBot `stateHash`). `Apply`/`LegalActions`/`Position` deferred to Task 3.
- [x] port TT probe/store with GoBot's fail-soft bound semantics (exact/lower/upper by ply+depth)
      — `TableEntry{depth,ply,flag,bestAction,values[4]}`, `FLAG_EXACT/LOWER/UPPER = 0/1/2` (GoBot
      iota); the flag *computation* is inline in `minimax` (Task 3), store is unconditional overwrite.
- [x] unit test: TT store then probe returns the stored entry with correct flag handling
      — `GoBotSearcherTest`: store/probe round-trip, flag constants, `stateHash` keys on hidden
      state (movesLeft/currentPlayer/neutralUsed), `running()` node-limit guard.
- [x] `./mvnw compile` clean — compile + full `./mvnw test` (80) green + `spotless:apply` clean.

### Task 3: Port minimax (AB + PVS) + maxN + fixed-depth ChooseDepth
- [x] port `minimax` faithfully: alpha-beta, PVS (full window first move; `alpha,alpha+1` null
      window then re-search on fail-high), TT probe/store, leaves via `HandTunedEval` (no
      quiescence), the exact move iteration/order GoBot uses
      — `GoBotSearcher.minimax`; move gen/order ported as `GoPosition` (`ForEachSearchAction` +
      `strategicNeutralPairs`) + `GoState` rules transitions (`applyGenerated`/`connected`/
      `advance`/`eliminate`). Search runs in `long` (fixed the pre-existing `INF_SCORE = 1<<60`
      int-overflow bug; GoBot's `int` is 64-bit). Incomplete-budget signalled via unchecked
      `SearchIncomplete` (fixed depth never throws; sets up Task 4's time-limited path).
- [x] port `maxN` (for >2 players; 1v1 uses minimax) and `atDepth`
      — `GoBotSearcher.maxN`/`atDepth` (+ `orderedChildren`/`preservingChildren`/`terminalScore(s)`/
      `activeCount`). maxN unexercised by the 1v1 fixture; ported for fidelity.
- [x] port `ChooseDepth(board, player, depth)` — deterministic, fully completed — as the parity
      entry point — `GoBotSearcher.chooseDepth(GoState,depth)` + `(Board,player,movesLeft,neutralUsed,depth)`
      convenience; leaves evaluated via ported `HandTunedEval` (GoState→Board).
- [x] `./mvnw test` green — full suite 80 green + `spotless:apply` clean. Verified against the
      Task-1 fixture with a throwaway harness: 412/412 records match action AND integer score at
      depths {3,5} (the official `GoBotSearchParityTest` lands in Task 5).

### Task 4: Port the opening book + Choose/time entry points
- [x] port `opening_book.go` (`openingBookMove` thick first-turn placement + void condition,
      `openingBookResult`) to Java
      — `search/gobot/GoOpeningBook.java`: `openingBookMove` (wedge = base-adjacent anchor + width-2
      advancing pair, oriented inward; voids on own cell outside block / not-first-turn / OOB
      collision) + `openingBookResult` (wraps as completed depth-0 `GoResult`, score via
      `HandTunedEval`). Added `GoState.toBoard()` helper.
- [x] port `Choose` (iterative deepening + time budget) and `chooseNodeBudget`; entry points try
      the book first, exactly as GoBot
      — `GoBotSearcher.choose`/`chooseWithDeadline` (wall-clock deadline via `running()`;
      `PRODUCTION_BUDGET_MILLIS=1000`) + `chooseNodeBudget` (node limit). Both consult
      `GoOpeningBook.openingBookResult` first, then `preservingFallback` + iterative deepening over
      `atDepth`; `SearchIncomplete` = GoBot's `!complete` break.
- [x] unit test: book triggers on a fresh opening turn and voids once own non-base cells appear
      — `GoOpeningBookTest` (7 tests): anchor placement, in-order block advance, void on own cell
      outside block, void on wrong-turn `movesLeft` invariant, `openingBookResult` wrapping,
      `chooseNodeBudget` takes book vs runs search when voided.
- [x] `./mvnw test` green — full suite 87 green (+7) + `spotless:apply` clean.

### Task 5: Make the GoBot search selectable + prove parity
- [x] wire the ported engine behind `SEARCH=GOBOT` (env/property, mirroring `EVAL=HANDTUNED`) in
      the bot path (`GameLoopHandler`/`SearchEngine` selection), using `HandTunedEval` leaves so
      `SEARCH=GOBOT EVAL=HANDTUNED` = full clone
      — `GameLoopHandler.USE_GOBOT_SEARCH` (System property/env `SEARCH=GOBOT`); when set, `makeMove`
      routes to `GoBotSearcher.choose(GoState.fromBoard(board,player,movesLeft,neutralUsed))` (book →
      iterative-deepening minimax → `HandTunedEval` leaf) and adapts `GoResult`→`SearchResult`. NNUE
      path untouched (default branch unchanged).
- [x] add `GoBotSearchParityTest`: for each oracle record assert
      `ChooseDepth(board, player, depth).action == record.action` (and score) — **must pass**
      — `GoBotSearchParityTest.fixedDepthMoveParityOnFixture`: 412/412 records match action AND
      integer score at depths {3,5} via `GoBotSearcher.chooseDepth(board,player,movesLeft,neutralUsed,depth)`.
- [x] `./mvnw test` green incl. parity; `./mvnw spotless:check` clean
      — full suite 88 green (+1); `spotless:check` BUILD SUCCESS.

### Task 6: Update plan notes
- [ ] record oracle fixture size/depths, any hidden-state findings, and the exact maintainer
      command to run the clone vs GoBot (`SEARCH=GOBOT EVAL=HANDTUNED` + `eval_java_vs_go.py`)

## Technical Details
- 1v1 live path: `openingBookResult` → iterative-deepening `minimax` (AB+PVS+TT) → `HandTunedEval`
  leaf. `maxN` only at >2 players.
- Parity is **move-identical at fixed depth** (deterministic). Same integer scores expected since
  the leaf eval is the already-parity `HandTunedEval`. A mismatch = an ordering/PVS/TT/book bug.
- Keep translation faithful; do not add quiescence or "improve" pruning — that would break parity.

## Post-Completion
*No checkboxes — maintainer-run after merge (the epic's real floor check):*

- Build with `SEARCH=GOBOT EVAL=HANDTUNED` and run `eval_java_vs_go.py 10` vs GoBot. Expect
  **~even / draws** (the clone should match GoBot). That confirms the parity floor and closes
  the epic's Phase-1 goal.
  - If the clone matches GoBot → parity achieved; Phase 2 (`0dj.3`, NNUE-on-top) can begin on a
    real rig, or we ship the clone as a strong bot.
  - If it still loses → a remaining port fidelity bug (or a state/rules mismatch between our Board
    and GoBot's game.State); the move-parity fixture should be expanded to localize it.
