# NNUE v2 Reference Accumulator: Contract Reconciliation + Python Parity

## Overview

`NNUEv2Accumulator.java` already exists but its `extractPattern()` uses a STALE
per-cell encoding (alphabet 0..14 with the manhattan-distance folded into each
cell symbol, `List<Integer>` dictionary keys, and it only emits a window for
NORMAL/FORTIFIED cells). That predates the finalized 3.1 pattern contract and
3.2 dictionary key format, so its keys do NOT match the promoted dictionary
`python/v2/nnue_v2_dictionary.json` (keys look like `"c0,c1,...,c24|bucket"`).

The canonical contract is already implemented, correctly, in
`PatternContract.java` (mirrors `python/v2/pattern_contract.py` exactly: alphabet
0..8, full 5x5 row-major window, distance bucket = min(manhattan-to-enemy-base, 7)
or 7 if no enemy base, and a window emitted for EVERY (r,c) whose 5x5 is not
all-empty/OOB). The canonical signature string is defined by
`python/v2/mine_patterns.py::window_signature`:
`",".join(str(s) for s in window.symbols) + "|" + str(window.distance_bucket)`.

**Goal:** rewrite `NNUEv2Accumulator.java` to build signatures via
`PatternContract.extractWindows` + that exact string format, look them up through
`PatternDictionary` (string keys, real promoted dictionary), count occurrences
per perspective (STM = active player, NSTM = 3 - active player), ignore
dictionary misses, and accumulate first-layer columns per occurrence into the two
perspective accumulators (full recompute). Must handle variable board sizes and
OOB edges (already inherited from the contract — no 12x12 hardcode). Prove
byte-for-byte parity against the Python contract on shared fixture boards.

**Do NOT change the Python contract** — it is the source of truth. Fix the Java
side only.

## Context (from discovery)

- Rewrite: `src/main/java/com/engine/nnue_trainer/v2/NNUEv2Accumulator.java`
  (currently uses stale `extractPattern` + `Map<List<Integer>,Integer>`).
- Reuse as-is (already contract-correct): `PatternContract.java`
  (`extractWindows`, `Window{symbols[25], distanceBucket}`), `PatternDictionary.java`
  (`load(Path)`, `lookup(String)->-1 on miss`).
- Board API: `Board(rows, cols)`, `board.rows/cols`, `getCell`, `setCell`,
  `isValidPos`; `Cell{int owner; CellKind kind}`; `CellKind{EMPTY,NORMAL,BASE,FORTIFIED,NEUTRAL}`.
- Python signature format authority: `python/v2/mine_patterns.py::window_signature`.
- Dictionary: `python/v2/nnue_v2_dictionary.json` (5571 patterns, string keys).
- Test to rewrite: `src/test/java/com/engine/nnue_trainer/v2/NNUEv2AccumulatorTest.java`
  (currently asserts the OLD broken encoding).
- Build/test: `./mvnw test -Dtest=NNUEv2AccumulatorTest`.

## Development Approach

- **Testing approach**: Regular (rewrite the accumulator, then the parity test).
- The parity test is the primary deliverable — it must load the SAME real
  dictionary both sides use and compare per-perspective (dictionary-id -> count)
  maps produced by Java against a committed fixture generated from the Python
  contract.
- Complete each task fully; run `./mvnw test -Dtest=NNUEv2AccumulatorTest`
  before moving on.
- Do not add new dependencies (Jackson is already available; use it for fixture JSON).

## Testing Strategy

- **Unit/parity test**: `NNUEv2AccumulatorTest` — rebuild fixture boards, run the
  Java accumulator, assert the (id -> count) map per perspective equals the
  committed Python-generated expectations for each board. Include at least one
  NON-12x12 board and one board with edge OOB windows (a piece in a corner).
- No e2e framework in this project.

## Progress Tracking
- Mark completed items with `[x]` immediately when done.
- Add newly discovered tasks with the ➕ prefix; blockers with ⚠️.

## Implementation Steps

### Task 1: Rewrite NNUEv2Accumulator to the canonical contract + string dictionary
- [ ] Replace the stale `extractPattern`/`Map<List<Integer>,Integer>` design.
      New fields: `PatternDictionary dict`, `float[][] hiddenWeights`
      (`[numPatterns][K]`, nullable), `float[] hiddenBias` (`[K]`, nullable),
      `int K`, `int denseSize` (default 14). Keep the K/bias dimension guards.
- [ ] Add `static String signature(PatternContract.Window w)` producing
      `",".join(symbols) + "|" + distanceBucket` — byte-identical to
      `python/v2/mine_patterns.py::window_signature`.
- [ ] Add `Map<Integer,Integer> countPatterns(Board board, int perspectiveOwner)`:
      call `PatternContract.extractWindows(board, perspectiveOwner)`, build each
      signature, `dict.lookup(sig)`, skip `-1` (unseen), and count occurrences
      per id (`LinkedHashMap`/`HashMap`, `merge(id,1,Integer::sum)`). This yields
      COUNTED occurrences, not booleans.
- [ ] Rewrite `computeFull(Board, int activePlayer, float[] denseFeatures)`:
      `nstm = 3 - activePlayer`; init both accumulators from `hiddenBias`
      (zeros if null); for STM use `countPatterns(board, activePlayer)`, for NSTM
      `countPatterns(board, nstm)`; for each (id,count) add `count * hiddenWeights[id][i]`.
      Output layout unchanged: `[accumSTM(K)] ++ [accumNSTM(K)] ++ [dense(denseSize)]`.
- [ ] Remove the old `extractPattern` method and the `List`/`Arrays` imports it needed.
- [ ] run `./mvnw test -Dtest=NNUEv2AccumulatorTest` compiles (test rewritten in Task 3).

### Task 2: Generate + commit a shared parity fixture from the Python contract
- [ ] Add `python/v2/gen_accumulator_fixture.py`: define a few boards in-process
      using `pattern_contract.Board/Cell/CellKind` — at minimum: (a) a small board
      with two players' pieces incl. an enemy BASE so distance buckets vary,
      (b) a NON-12x12 board of a different size, (c) a board with pieces in
      corners so windows hit OOB edges. For each board and each perspective
      (stm_owner in {1,2}), run `PatternContract.extract_windows`, build
      `window_signature`, look up ids in `nnue_v2_dictionary.json` (skip misses),
      and count per id.
- [ ] Write `src/test/resources/v2/accumulator_parity_fixture.json`:
      a list of `{name, rows, cols, cells:[{r,c,owner,kind}], expected:{"1":{id:count},"2":{id:count}}}`
      where `kind` is the CellKind NAME (EMPTY/NORMAL/BASE/FORTIFIED/NEUTRAL) and
      the perspective keys "1"/"2" map owner -> (id->count). Only non-empty cells
      listed. Commit both the generator and the fixture.
- [ ] Ensure at least one board's expected maps are non-empty for BOTH perspectives
      (sanity that the dictionary actually contains the produced signatures);
      if a hand-built board yields all-misses, adjust it until it hits real ids.
- [ ] run the generator (`python3 python/v2/gen_accumulator_fixture.py`) and
      confirm it writes deterministic output.

### Task 3: Rewrite NNUEv2AccumulatorTest as a real parity test
- [ ] Load `python/v2/nnue_v2_dictionary.json` via `PatternDictionary.load`.
- [ ] Load `src/test/resources/v2/accumulator_parity_fixture.json` (Jackson).
- [ ] For each fixture board: reconstruct the `Board` (map kind name -> CellKind),
      call `accumulator.countPatterns(board, 1)` and `(board, 2)`, and assert the
      resulting (id->count) map equals the fixture's `expected["1"]`/`["2"]`.
- [ ] Add one direct assertion that `NNUEv2Accumulator.signature(w)` for a
      hand-built Window equals the expected `"...|bucket"` string (guards the
      exact format).
- [ ] Add one `computeFull` assertion on a tiny board with a stub weights matrix
      sized to `dict.numPatterns()`, verifying counts are applied multiplicatively
      (count * weight) and the output length is `2*K + denseSize`.
- [ ] run `./mvnw test -Dtest=NNUEv2AccumulatorTest` — parity test green.

### Task 4: Verify acceptance criteria
- [ ] Variable board size + OOB handled (fixture includes a non-12x12 and a
      corner/edge board); no 12x12 hardcode anywhere in the accumulator.
- [ ] Unseen patterns ignored (lookup -1 skipped), counts (not booleans) used.
- [ ] STM/NSTM canonicalization matches the contract (perspective owner passed to
      `extractWindows`).
- [ ] run full v2 suite: `./mvnw test -Dtest='com.engine.nnue_trainer.v2.*'`.

## Technical Details

- Signature: symbols are ints 0..8, joined with `,`, then `|`, then bucket 0..7.
  Example canonical key from the dictionary: `"0,0,...,0,4|7"`.
- Perspective: `extractWindows(board, owner)` normalizes self/opponent by
  `cell.owner == owner`. STM passes activePlayer; NSTM passes `3-activePlayer`.
- Counted occurrences: the SAME (signature) can recur across many window centers;
  each recurrence adds the column once (so `count * weight`).
- Output vector unchanged so downstream consumers keep working.

## Post-Completion

**Manual verification** (optional):
- Cross-check: a one-off script that runs the Python contract and the Java
  accumulator on the fixture boards and diffs the (id->count) maps — expected to
  already be equal by construction of the fixture.
