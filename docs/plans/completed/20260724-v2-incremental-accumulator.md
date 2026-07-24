# NNUE v2: Incremental Changed-Window Accumulator Updates

## Overview

Add incremental accumulator updates to `NNUEv2Accumulator` (bead
`nnue-trainer-d4a.1.2`, stacked on 1.1) that stay EXACTLY equal to a full
recompute. When a move changes one or more cells, only the 5x5 windows that
overlap a changed cell can change; every other window is byte-identical and
must be left untouched. The update:

1. Builds the UNION of all window centers overlapping any changed cell (a
   single changed cell affects up to 25 window centers), deduped so each
   affected window is processed exactly ONCE (multi-cell moves never
   double-subtract/double-add).
2. For each affected window, computes its dictionary id on the OLD board
   (decrement its occurrence count once if recognized) and on the NEW board
   (increment once if recognized). Dictionary misses are skipped.
3. Preserves COUNTED per-occurrence semantics and updates BOTH the STM and
   NSTM perspectives.

**Exactness strategy (the crux):** the incrementally-maintained state is the
integer per-perspective `(id -> count)` maps — the genuinely incremental,
exact structure the doc describes ("subtract that pattern weight column once"
== decrement count once; "add once" == increment count once). The float
accumulator / assembled output is DERIVED from those counts through the SAME
reduction `computeFull` uses, iterating ids in a deterministic (sorted) order.
Because integer counts are exact and both the full and incremental paths run
the identical float reduction over identical count maps, the results are
byte-identical — immune to floating-point non-associativity that would break a
naive "add/subtract float columns directly" scheme.

Merge target: `nnue-v2-1.1-accumulator` branch (stacked PR), NOT master/v2/3.2.

## Context (from discovery)

- **`src/main/java/com/engine/nnue_trainer/v2/NNUEv2Accumulator.java`** — 1.1
  contract: `signature(Window)`, `countPatterns(board, owner)` (HashMap
  id->count, misses ignored), `computeFull(board, activePlayer, dense)` (STM =
  activePlayer, NSTM = 3-activePlayer; accum = bias + sum(count*col)),
  `assemble(stm,nstm,dense)`. Constructors allow null weights/bias.
- **`src/main/java/com/engine/nnue_trainer/v2/PatternContract.java`** —
  `extractWindows(board, stmOwner)` scans every center, builds 25 symbols,
  emits a `Window` only if NOT all-empty/OOB, bucket =
  `getDistanceBucket(center, enemyBase)`; `findEnemyBase(board, owner)` returns
  the BASE cell not owned by `owner` (may be null); `getSymbol`, `Window`.
- **`src/main/java/com/engine/nnue_trainer/board/Board.java`** — variable
  `rows`/`cols`, `getCell(r,c)` returns null OOB, `setCell`, `isValidPos`.
  `Cell` and `Pos` both have value `equals`/`hashCode`.
- **Test/fixture (from 1.1):**
  `src/test/java/com/engine/nnue_trainer/v2/NNUEv2AccumulatorTest.java`,
  `src/test/resources/v2/accumulator_parity_fixture.json`, dictionary
  `python/v2/nnue_v2_dictionary.json` (string signature -> id).

### Key correctness facts

- The window signature depends on the 25 symbols AND the distance bucket
  (center-to-enemy-base Manhattan distance). If the enemy base MOVES between
  old and new board, EVERY window's bucket can shift, so the "only affected
  windows change" invariant breaks. Guard: if `findEnemyBase` differs between
  old and new board for a perspective, fall back to a full count recompute for
  THAT perspective. Bases are static in normal play, so this guard is rarely
  hit but keeps parity exact.
- Full recompute never stores zero counts, so incremental MUST drop an entry
  when its count reaches 0, or the maps won't compare equal.
- Affected window centers = all `(wr,wc)` valid on the board with
  `|wr-cr|<=2 && |wc-cc|<=2` for some changed cell `(cr,cc)`. Dedup via a set.
- No 12x12 hardcode anywhere: derive everything from `board.rows/cols` and the
  window radius (2).

## Development Approach

- **Testing approach**: Regular (extend the 1.1 contract, then add parity
  tests). Reuse `signature`/`extractWindows`/`PatternDictionary`; do NOT
  reinvent signatures or touch the Python side or the signature contract.
- Small focused changes; run `./mvnw test -Dtest=NNUEv2AccumulatorTest` after
  each task; all tests pass before the next task.
- Do NOT change `computeFull`'s observable float output in a way that breaks
  existing tests (existing tests use null or all-ones weights, so switching the
  reduction to a deterministic sorted-id order is safe).

## Testing Strategy

- **Unit tests** in `NNUEv2AccumulatorTest`: incremental == full recompute for
  single-cell change, multi-cell overlapping move, edge/OOB windows,
  repeated-occurrence pattern ids, unseen patterns (dict miss), and both
  STM/NSTM perspectives (run with activePlayer 1 AND 2). Plus a
  randomized/property test that plays a sequence of random moves and asserts
  incremental == full at EVERY step (both the integer count maps and the
  derived float output).
- No e2e tests in this repo.

## Progress Tracking
- Mark completed items with `[x]` immediately when done
- Add newly discovered tasks with ➕ prefix
- Document issues/blockers with ⚠️ prefix

## Implementation Steps

### Task 1: Factor a single-window builder in PatternContract
- [x] Add `public static Window buildWindow(Board board, int r, int c, int stmOwner, Pos enemyBase)` that builds the 25 symbols for center `(r,c)`, returns `null` if all-empty/OOB (emission rule), else a `Window` with `getDistanceBucket(r,c,enemyBase)` — identical logic to the inner loop of `extractWindows`.
- [x] Refactor `extractWindows` to call `buildWindow` per center (single source of truth; behavior unchanged).
- [x] Run `./mvnw test -Dtest=PatternContractTest,NNUEv2AccumulatorTest` — must still pass (proves the refactor is behavior-preserving) before Task 2.

### Task 2: Add count-based State + deterministic reduction to NNUEv2Accumulator
- [x] Add a public nested `State` holding `Map<Integer,Integer> stmCounts`, `Map<Integer,Integer> nstmCounts`, and `int activePlayer`, with accessors.
- [x] Add `public State newState(Board board, int activePlayer)` that builds both count maps via the existing `countPatterns` (STM=activePlayer, NSTM=3-activePlayer).
- [x] Add a private `accumFromCounts(Map<Integer,Integer> counts)` that returns `float[K]` = bias + sum over ids **in ascending sorted order** of `count*col` (handles null weights -> bias only, null bias -> zeros), and a `public float[] output(State state, float[] dense)` that assembles `[STM, NSTM, dense]` from the two count maps via `accumFromCounts` + the existing `assemble`.
- [x] Refactor `computeFull(board, activePlayer, dense)` to `return output(newState(board, activePlayer), dense)` so full and incremental share the IDENTICAL float reduction (guarantees byte-exact parity). Keep the existing constructors, `signature`, and `countPatterns` unchanged.
- [x] Add a package-private/private `idAt(Board board, int r, int c, int owner, Pos enemyBase)` returning `dict.lookup(signature(buildWindow(...)))` or `-1` when the window is unemitted (buildWindow null) or a dict miss.
- [x] Run `./mvnw test -Dtest=NNUEv2AccumulatorTest` — existing parity + multiplicativity tests must still pass before Task 3.

### Task 3: Implement the incremental applyMove
- [x] Add `public void applyMove(State state, Board oldBoard, Board newBoard, java.util.Collection<Pos> changedCells)` using `state.activePlayer` (STM=activePlayer, NSTM=3-activePlayer). No-op when `hiddenWeights == null`? No — counts are weight-independent, so still update counts (output derives correctly regardless).
- [x] Per perspective owner: compute `oldBase = findEnemyBase(oldBoard, owner)` and `newBase = findEnemyBase(newBoard, owner)`; if `!Objects.equals(oldBase,newBase)` replace that perspective's counts with `countPatterns(newBoard, owner)` (full recompute fallback for board-wide bucket shift) and return.
- [x] Otherwise build the deduped union of affected centers (`|wr-cr|<=2 && |wc-cc|<=2`, valid on newBoard) via a `Set`; for each center compute `oldId = idAt(oldBoard,...,oldBase)` and `newId = idAt(newBoard,...,newBase)`; if `oldId == newId` skip; else `dec(counts,oldId)` when `oldId>=0` (remove entry at 0) and `inc(counts,newId)` when `newId>=0`.
- [x] Add a `public static java.util.List<Pos> diffCells(Board oldBoard, Board newBoard)` convenience helper (cells differing by `Cell.equals`; requires same dims) so callers/tests can derive `changedCells` honestly.
- [x] Run `./mvnw test -Dtest=NNUEv2AccumulatorTest` — must pass before Task 4.

### Task 4: Parity tests for every required case
- [x] Add a helper in the test that loads the real dictionary and builds an accumulator with deterministic non-trivial `float[numPatterns][K]` weights (e.g. `weights[id][i] = (float)((id*31 + i) % 7) - 3`) and a non-null bias, so float parity is a real check (not all-ones).
- [x] Add a helper `assertParity(oldBoard,newBoard,activePlayer)`: `State s = newState(old); applyMove(s, old, new, diffCells(old,new));` then assert `s.stmCounts()/nstmCounts()` equal `newState(new).*` counts AND `output(s,dense)` array-equals `computeFull(new, activePlayer, dense)` EXACTLY (`assertArrayEquals` with delta 0), for activePlayer 1 AND 2 (covers STM+NSTM swap).
- [x] Test: single-cell change (place one piece on an interior empty cell).
- [x] Test: multi-cell move with overlapping windows (two changed cells within 4 of each other so their affected-window sets overlap — proves each shared window is processed once).
- [x] Test: edge/OOB windows (changed cell in a corner / on the border of a small board so windows include OUT_OF_BOUNDS).
- [x] Test: repeated occurrences of the same pattern id (two identical isolated pieces like the fixture's repeated_pattern board; move one, assert the shared id's count decrements by exactly the right amount).
- [x] Test: unseen pattern / dict miss (windows near a static base carry a non-7 bucket the all-bucket-7 dictionary never contains — guaranteed miss; parity still holds, i.e. misses are skipped on both sides).
- [x] Test: base-move fallback (a board where the enemy base position differs old->new, e.g. remove/relocate a BASE cell — assert parity still exact via the fallback path).
- [x] Run `./mvnw test -Dtest=NNUEv2AccumulatorTest` — must pass before Task 5.

### Task 5: Randomized property parity test
- [x] Add a seeded (`new Random(SEED)`) property test: start from a small/medium board with two bases, then for N iterations mutate 1–3 random cells (place/convert/clear NORMAL/FORTIFIED/NEUTRAL, occasionally a base to exercise the fallback), snapshot old board (deep copy), apply the change to a new board, `applyMove(state, old, new, diffCells(...))`, and assert incremental counts AND float output equal full recompute at EVERY step. Run with activePlayer 1 and 2.
- [x] Add a tiny deep-copy board helper in the test (new Board + copy cells) since Board has no clone. (Reused existing `copy` helper from Task 4.)
- [x] Run `./mvnw test -Dtest=NNUEv2AccumulatorTest` — must pass before Task 6.

### Task 6: Verify acceptance criteria
- [x] Verify all six required parity cases + the property test are present and green.
- [x] Confirm no 12x12 hardcode, signature contract unchanged, Python side untouched, PatternDictionary/extractWindows/signature reused (not reinvented).
- [x] Run full `./mvnw test -Dtest=NNUEv2AccumulatorTest,PatternContractTest,PatternDictionaryTest,DenseFeaturesTest` — all green.

## Technical Details

- **State**: `stmCounts`, `nstmCounts` (`HashMap<Integer,Integer>`, positive
  counts only), `activePlayer`. STM perspective owner = `activePlayer`, NSTM =
  `3 - activePlayer`.
- **`accumFromCounts`**: `float[] a = bias?.clone() : new float[K]; for (int id : sortedKeys) for i: a[i] += count*col[id][i];` — deterministic order == what `computeFull` now uses.
- **`applyMove` per perspective**: base-change guard -> else union-of-windows,
  dedup set of packed `(wr<<32)|wc`, `oldId==newId` skip, `dec`/`inc` with
  zero-removal.
- **`diffCells`**: iterate all cells, collect `Pos` where `!oldCell.equals(newCell)`.
- Window radius is the literal `2` (5x5), derived nowhere from board size.

## Post-Completion

**Manual verification** (informational, no checkbox):
- 1.3 will integrate this behind the evaluator opt-in flag and benchmark NPS;
  the truly-incremental float-domain update (skip re-deriving from counts) is a
  possible later optimization if profiling shows the count reduction is hot —
  deferred because exact parity is the acceptance criterion here.
- Open the PR with base `nnue-v2-1.1-accumulator` (stacked), draft.
