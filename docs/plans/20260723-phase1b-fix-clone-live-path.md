# Fix GoBot clone live-path bug (bd nnue-trainer-0dj.4)

## Overview

The GoBot clone (`SEARCH=GOBOT EVAL=HANDTUNED`) loses **0–10** to GoBot despite:
- **412/412 fixed-depth `ChooseDepth` move+score parity** (search algo + eval + move-gen faithful),
- both flags confirmed active (integer eval scores, no fallback), and
- GoBot's exact `ProductionBudget=1s`, reaching comparable live depth/nodes (~depth 6–7 / 30k).

A faithful clone should **draw** GoBot, so the bug is in the **live path the fixed-depth fixture
never exercised**. Two structural gaps:
1. **Live play uses `choose` (Choose + wall-clock time), but the fixture verified `chooseDepth`
   (fixed depth).** The iterative-deepening `choose` path is unverified.
2. **The live *inputs* to `GoState.fromBoard`** — `myPlayerIndex`, `movesLeft` (per-action within
   the 3-actions-per-turn structure), `neutralUsed`, board orientation — come from the server
   snapshot in `GameLoopHandler` and may not match the oracle's conventions. (Note: `fromBoard`
   itself is shared with the fixture and IS parity-verified — the suspect is the *inputs*, and the
   `GoResult.action → server move` translation back.)

`ChooseNodeBudget` (node-limited, **deterministic**, no wall-clock) gives us a deterministic
live-path oracle — the key tool to localize this, exactly like `ChooseDepth` did for Phase 1.

## Goal

Find and fix the live-path divergence so the clone **matches GoBot in play**. Deliver:
(a) a deterministic `ChooseNodeBudget` move-parity oracle + test, (b) an audit/test of the live
`GameLoopHandler` → `GoState` inputs and the action→server-move translation, (c) the fix.
Acceptance is the new parity tests passing; the live clone-vs-GoBot re-measure is maintainer-run.

## Context (from discovery)

- **Live entry:** `GameLoopHandler.gobotSearch(board, movesLeft, neutralUsed)` (~line 249) →
  `GoBotSearcher.choose(GoState.fromBoard(board, myPlayerIndex, movesLeft, neutralUsed))`. Then
  `makeMove` translates `GoResult.action` into the server move/neutrals message.
- **Search ports:** `src/main/java/com/engine/nnue_trainer/search/gobot/` — `GoBotSearcher`
  (`chooseDepth` verified; `choose`/`chooseWithDeadline` and `chooseNodeBudget` are the live/
  deterministic entries), `GoState` (`fromBoard` shared+verified), `GoPosition`, `GoOpeningBook`,
  `TableEntry`, `GoResult`, `RootMove`.
- **GoBot source:** `../virusgame/backend/search/search.go` — `Choose` (time),
  `ChooseNodeBudget(state, limit)` (deterministic, search.go:96), `ChooseDepth` (fixed depth).
- **Phase-1 oracle tool:** the Go tool added in Phase 1 (mirrors `arena/cmd/staticevalgen`) emits
  `ChooseDepth` results; extend it to also emit `ChooseNodeBudget` results.
- **Existing fixed-depth fixture:** `src/test/resources/gobot_search_parity.jsonl` +
  `GoBotSearchParityTest` (the model to mirror for the node-budget test).
- **Symptoms recorded on 0dj.4:** live scores swing ±27533 (within hand-tuned eval integer range,
  inconclusive alone); depth/nodes look faithful; loss is total (0–10) → systematic wrong moves.

## Development Approach

- **Debug-by-oracle:** build the deterministic `ChooseNodeBudget` oracle first; the parity test
  will FAIL initially and localize whether the divergence is in `choose`/iterative-deepening
  (search-side) vs the live inputs / move translation (wiring-side). Fix accordingly.
- Faithful translation only; do not "improve" the search. Do not touch the NNUE path.
- Acceptance = deterministic parity tests pass. Do NOT run live GoBot games (maintainer does).

## Testing Strategy

- **`ChooseNodeBudget` move-parity test (required):** deterministic oracle (a fixed node limit,
  e.g. 50k) over the same diverse positions; assert Java `chooseNodeBudget(state, N).action ==
  GoBot's action (and score). This covers the full iterative-deepening + book + Choose logic
  deterministically (unlike time-based `choose`).
- **Live-input construction test:** given a representative server snapshot (board + whose-turn +
  movesLeft + neutralUsed as the server sends them), assert `GameLoopHandler` feeds `fromBoard`
  the values that produce the SAME `GoState` the oracle would for that position (perspective,
  movesLeft-in-turn, orientation, neutralUsed indexing).
- **Move-translation round-trip test:** a `GoResult.action` (move / neutrals) → server message →
  parsed back yields the same action (row/col/type; the 3-per-turn + neutral-placement cases).
- Keep the full suite green; `ChooseDepth` parity must still pass; spotless clean.

## Progress Tracking
- Mark `[x]` immediately. `➕` new tasks, `⚠️` blockers. Keep this file in sync.

## Implementation Steps

### Task 1: Deterministic ChooseNodeBudget parity oracle
- [ ] extend the Phase-1 Go oracle tool in `../virusgame` to also emit, per position,
      `search.ChooseNodeBudget(state, LIMIT)` → `{board, player, movesLeft, neutralUsed, nodeLimit,
      action, score}` (a fixed LIMIT, e.g. 50000; document it)
- [ ] generate over the same diverse positions; check a trimmed fixture into
      `src/test/resources/gobot_nodebudget_parity.jsonl`
- [ ] no code test yet — fixture feeds Task 2

### Task 2: ChooseNodeBudget parity test (localizes search-side divergence)
- [ ] add `GoBotNodeBudgetParityTest`: for each record assert
      `chooseNodeBudget(fromBoard(...), nodeLimit).action == record.action` (and score)
- [ ] if it FAILS: the bug is in `choose`/`chooseNodeBudget`/iterative-deepening/`atDepth` root
      move selection — fix the port until it matches GoBot (compare against `ChooseDepth`, which is
      known-correct, at the depth the budget reaches)
- [ ] if it PASSES: the search side is faithful → the bug is wiring (Tasks 3–4)
- [ ] `./mvnw test` green

### Task 3: Audit + test the live GoState inputs
- [ ] trace `GameLoopHandler`: how it derives `myPlayerIndex`, `movesLeft`, `neutralUsed`, and the
      board from the server snapshot, across the 3 actions of a turn; compare to what the oracle/
      `fromBoard` expects (movesLeft semantics per-action, player indexing, board orientation,
      neutralUsed length/index)
- [ ] add a test building a `GoState` from a representative server-snapshot-shaped input and assert
      it equals the oracle's `GoState` for the same logical position (or that the derived
      movesLeft/perspective are correct)
- [ ] fix any mismatch (this is the most likely bug given fixed-depth parity already passes)
- [ ] `./mvnw test` green

### Task 4: Audit + test the action→server-move translation
- [ ] verify `makeMove` translates every `GoResult.action` kind (grow/attack move, neutrals
      placement) to the correct server message (row/col/type), including the 3-per-turn flow
- [ ] add a round-trip test (action → message → parse) asserting equality
- [ ] fix any mismatch
- [ ] `./mvnw test` green

### Task 5: Verify + notes
- [ ] full suite green incl. both parity tests (`ChooseDepth` + `ChooseNodeBudget`); spotless clean
- [ ] record in the plan which layer had the bug and the exact maintainer command to re-measure
      (`SEARCH=GOBOT EVAL=HANDTUNED` + `eval_java_vs_go.py 10`)

## Technical Details
- `ChooseNodeBudget` is deterministic (node-limited), so it is a valid golden oracle where
  time-based `Choose` is not. Live play uses `choose` (time) but shares the same iterative-
  deepening core, so node-budget parity strongly implies live-search fidelity.
- A faithful clone should draw GoBot; the target is parity (≈even), not beating it.

## Post-Completion
*No checkboxes — maintainer-run after merge:*
- Re-run the clone: `SEARCH=GOBOT EVAL=HANDTUNED` + `eval_java_vs_go.py 10` vs GoBot. Expect
  ~even/draws = **parity floor achieved**. If it draws, the epic's Phase-1 goal is met and Phase 2
  (`0dj.3`, NNUE-on-top) opens. If it still loses, expand the oracle to the exact live positions
  where it diverges (log the clone's chosen move vs GoBot's move in a live game to localize).
