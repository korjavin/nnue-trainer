# Mine Promoted 5x5 Pattern Dictionary for NNUE v2

> **CRITICAL: All PRs for this task target the `v2` branch, NOT `master`.**
> Bead: `nnue-trainer-d4a.3.2`.

## Overview
Build the offline pattern-mining pipeline that auto-extracts the promoted 5x5
pattern dictionary from REAL game positions. The pipeline:
1. Decodes real board positions from `dataset.json`.
2. Extracts active 5x5 windows using the merged `python/v2/pattern_contract.py`
   (`PatternContract.extract_windows`) — do NOT reinvent signatures or
   canonicalization; perspective-normalization (SELF/OPPONENT) is already done
   there.
3. Serializes each window into a canonical signature string, counts occurrences.
4. Promotes signatures with count >= `min_count` into an exact dictionary and
   exports `python/v2/nnue_v2_dictionary.json` (`pattern_to_id` map + metadata).
5. Java loader (`PatternDictionary`) consumes the dictionary for lookup with
   miss handling.

## Context (from discovery)
- **Signature contract**: `python/v2/pattern_contract.py`.
  `PatternContract.extract_windows(board, stm_owner)` returns `Window` objects
  with `symbols` (list of 25 ints, alphabet 0..8) and `distance_bucket` (0..7).
  It already skips all-empty / all-OOB windows and normalizes ownership. REUSE
  IT. A `Window` is what we hash.
- **Data source decision — use `dataset.json`** (repo root, 1048 real
  positions). It is the largest real-position corpus with recoverable full board
  state. Its `features` field is the v1 864-dim one-hot = 12x12x6.
  - v1 encoding per `src/main/java/.../nnue/BoardFeatureMapper.java`: each of the
    144 cells has a 6-dim one-hot at offset `cellIndex*6`:
    `0=EMPTY, 1=NORMAL_self, 2=NORMAL_opp, 3=FORTIFIED_self, 4=FORTIFIED_opp, 5=NEUTRAL`.
    Perspective is already the active player's, so decode with owner `1`=self,
    `2`=opponent and mine with `stm_owner=1`.
  - **KNOWN LIMITATION (be honest in output):** v1 encoding does NOT store BASE
    cells (they fall through to EMPTY), so decoded boards have no base →
    `find_enemy_base` returns None → `distance_bucket` is always `7`. Patterns
    are still valid; only the distance-context dimension is degenerate for this
    corpus. This is acceptable for a prototype and is called out in the report.
  - Rejected alternatives: `/home/iv/games.db` has only 18 games; the non-12x12
    ones (5x5, 5x7) have empty PGNs (length 4) and the 12x12 ones need a full
    Virus PGN replayer (heavy, Java-only) → near-zero real positions gained.
    `SelfPlayGenerator` can generate more but emits v1 format and needs code
    changes → deferred to a follow-up bead for scaled/variable-size generation.
- **Board size independence**: the miner reads `board.rows`/`board.cols` from the
  decoded board and never hardcodes 12. `dataset.json` happens to be 12x12, but
  the code accepts any size. The decoder derives side length as
  `int(sqrt(len(features)/6))` and asserts it squares back — no literal 12.
- **Java JSON**: Jackson (`com.fasterxml.jackson.core:jackson-databind`) is
  already a dependency.
- **Python test convention**: tests import `from python.v2.<mod> import ...` and
  run via `python3 -m unittest discover -s python/v2 -p "*_test.py"` from repo
  root (PEP-420 namespace packages; no `__init__.py`). Match this.

## Development Approach
- Regular (code first, then tests) — the contract is already tested.
- Deterministic output is a hard requirement: same input + same `min_count` →
  byte-identical `nnue_v2_dictionary.json`. Achieve determinism by (a) iterating
  positions in file order, (b) assigning feature IDs by sorting promoted
  signatures with a stable key `(-count, signature_string)`, and
  (c) `json.dump(..., sort_keys=True, indent=2)`.
- Do NOT dedup 90°/mirror rotations. Do NOT coalesce into singleton cells. Do
  NOT hardcode 12x12.
- Every task ends with passing tests before the next.

## Testing Strategy
- Python unit tests in `python/v2/mine_patterns_test.py`.
- Java unit test in `src/test/java/com/engine/nnue_trainer/v2/PatternDictionaryTest.java`.

## Progress Tracking
- Mark `[x]` when done; add ➕ for new tasks, ⚠️ for blockers.

## Implementation Steps

### Task 1: Signature + board-decoder + miner core in `python/v2/mine_patterns.py`
- [ ] add `window_signature(window)` → deterministic string:
      `",".join(str(s) for s in window.symbols) + "|" + str(window.distance_bucket)`.
      This IS the canonical signature (perspective already normalized by the
      contract). No rotation/mirror dedup.
- [ ] add `decode_v1_record(features)` → `pattern_contract.Board`: derive
      `side = int(round(len(features)/6) ** 0.5)`, assert `side*side*6 == len(features)`,
      build `Board(side, side)`, map each cell's 6-way one-hot to
      `Cell(owner, CellKind)` per the v1 encoding above (owner 1=self, 2=opp).
      NO literal board-size constant.
- [ ] add `iter_boards(dataset_path)` generator: load JSON, yield a decoded
      Board per record (in file order).
- [ ] add `count_signatures(boards, stm_owner=1)` → `collections.Counter`:
      for each board call `PatternContract.extract_windows`, signature each
      window, increment counter. Also return total window count for coverage.
- [ ] add `build_dictionary(counter, min_count)` → `(pattern_to_id, retained,
      total_promoted_occurrences)`: keep signatures with `count >= min_count`,
      sort by `(-count, signature)`, assign ids `0..N-1`.
- [ ] write tests (Task 2) — do not proceed until they pass.

### Task 2: `python/v2/mine_patterns_test.py`
- [ ] test `window_signature` is stable/deterministic for a known Window.
- [ ] test `decode_v1_record` round-trips a hand-built 864-vector (place a
      NORMAL_self, a NORMAL_opp, a NEUTRAL) into the right cells and that a
      non-square/short vector raises.
- [ ] test `count_signatures` on a tiny synthetic board counts the expected
      number of active windows (reuse the contract's known emission counts).
- [ ] test `build_dictionary` promotes only `count >= min_count`, assigns
      contiguous ids `0..N-1`, and is deterministic (same input twice → identical
      map).
- [ ] test determinism end-to-end: mining the same list of boards twice yields
      byte-identical serialized `pattern_to_id`.
- [ ] run `python3 -m unittest discover -s python/v2 -p "*_test.py"` — must pass.

### Task 3: CLI export + generate the `nnue_v2_dictionary.json` artifact
- [ ] add `export_dictionary(pattern_to_id, min_count, out_path)` writing
      `{"pattern_to_id": {...}, "metadata": {"num_patterns": N, "min_count": M,
      "version": 2}}` with `json.dump(..., sort_keys=True, indent=2)`.
- [ ] add `main()` / `if __name__ == "__main__":` with argparse:
      `--dataset` (default repo-root `dataset.json`), `--min-count` (default
      chosen after measuring — start at a value that lands the dict in range or,
      if unreachable, the value giving best honest coverage), `--out` (default
      `python/v2/nnue_v2_dictionary.json`). Print: dataset used, total windows,
      distinct signatures, chosen `min_count`, `num_patterns`, and **retained
      coverage** = promoted-occurrences / total-window-occurrences (as %).
      NO silent truncation — if fewer/more than 2k–10k, print an explicit note.
- [ ] run `python3 python/v2/mine_patterns.py` and commit the generated
      `python/v2/nnue_v2_dictionary.json`.
- [ ] verify the JSON parses and `metadata.num_patterns == len(pattern_to_id)`.

### Task 4: `PatternDictionary.java` loader
- [ ] create `src/main/java/com/engine/nnue_trainer/v2/PatternDictionary.java`:
      load a dictionary JSON (path or InputStream) via Jackson into
      `Map<String,Integer> patternToId` + parse metadata (`numPatterns`,
      `minCount`, `version`).
- [ ] method `int lookup(String signature)` → id, or `-1` on miss (unseen
      pattern ignored per spec). Add `boolean contains(String)` and `int size()`.
- [ ] no hardcoded board size anywhere.
- [ ] write tests (Task 5).

### Task 5: `PatternDictionaryTest.java`
- [ ] load the committed `python/v2/nnue_v2_dictionary.json` (resolve via repo
      path); assert `size() == metadata.num_patterns` and `> 0`.
- [ ] assert a known signature key from the file maps to its stored id.
- [ ] assert `lookup` of a fabricated unseen signature returns `-1`
      (miss handling).
- [ ] run `./mvnw test -Dtest=PatternDictionaryTest` — must pass.

### Task 6: Verify acceptance criteria
- [ ] `python3 -m unittest discover -s python/v2 -p "*_test.py"` passes.
- [ ] `./mvnw test -Dtest=PatternDictionaryTest` passes.
- [ ] `python3 python/v2/mine_patterns.py` runs and prints threshold + dict size
      + retained coverage; artifact committed.
- [ ] confirm no `12` board-size literal in `mine_patterns.py` or
      `PatternDictionary.java`.
- [ ] confirm signatures are NOT rotation/mirror-deduped and NOT coalesced.

## Post-Completion
*Informational — no checkboxes.*

**Follow-up bead to file** (via `bd create`): scaled + variable-board-size
self-play data generation — modify/extend `SelfPlayGenerator` (now on v2) to
emit raw board snapshots (with BASE cells preserved) so mining can run at real
scale on non-12x12 boards and exercise real `distance_bucket` values. The
current dictionary is proven on decoded `dataset.json` (12x12, no bases) as a
pipeline proof; re-mine on the richer corpus once available.

**Honesty note for the PR/report**: state the data source (`dataset.json`),
the chosen `min_count`, the dictionary size, retained coverage %, and the
base-cell / distance-bucket limitation of the v1-decoded corpus.
