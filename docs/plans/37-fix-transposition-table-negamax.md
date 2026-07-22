# Implementation Plan - Fix Transposition Table Regression & Refactor to Negamax (nnue-trainer-raz.2.3.1)

> **TARGET BRANCH**: `master` (This is a fix on top of PR #38 on master).

## Issue & Bead
Bead: `nnue-trainer-raz.2.3.1` (P0 Bug: Fix the Transposition Table - confirmed cause of PR #38 search regression).

## Problem & Root Cause
In-repo A/B benchmarks (`SearchAB`) revealed that enabling the Transposition Table (`USE_TT=true`) caused a search regression. The root cause is that the non-negamax (dual max/min branch) alpha-beta search inverted TT bound semantics (`FLAG_LOWER_BOUND` / `FLAG_UPPER_BOUND`) between maximizer and minimizer nodes, and stored fail-soft scores incorrectly when probing with non-exact bounds across different alpha-beta windows. Furthermore, TT keys did not include side-to-move explicitly.

## Design Goals
1. **Refactor `alphaBeta` to Negamax Form** (or correct the perspective-aware TT bound logic):
   - In Negamax, evaluations are always relative to the side to move (`sideToMove`).
   - Recursive call negates score and flips bounds: `score = -alphaBeta(child, ..., -beta, -alpha, ...)`
   - `TTEntry` flags are standard across all nodes:
     - If `score <= alpha_orig`: `FLAG_UPPER_BOUND` (fail-low)
     - If `score >= beta`: `FLAG_LOWER_BOUND` (fail-high)
     - Otherwise: `FLAG_EXACT`
2. **Zobrist Side-to-Move**:
   - Ensure `Zobrist.computeHash` includes side-to-move XOR key.
3. **TT Toggles & Clearing**:
   - Support `useTT` and `useQuiescence` flags (controllable via System properties `-DUSE_TT=false`, `-DUSE_QUIESCENCE=false`).
   - Automatically clear search state or probe TT safely across searches.
4. **Unit Tests & A/B Verification**:
   - Add unit test in `SearchEngineTest.java` verifying that at fixed depth, `SearchEngine` with TT produces the exact same best move as with TT disabled.
   - Run `SearchAB` to verify that `SearchEngine` with TT active beats baseline search.

## Step-by-Step Implementation

### Step 1: Update `Zobrist.java`
- Incorporate `player` (side to move) into Zobrist hash computation using a dedicated random key for `sideToMove`.

### Step 2: Refactor `SearchEngine.java`
- Add `useTT` and `useQuiescence` boolean fields (default `true`, overridable via system properties).
- Refactor `alphaBeta` search loop and TT probe/store logic to use correct perspective-normalized bounds (or Negamax form).
- Ensure TT probe checks:
  - `EXACT`: return `score`
  - `LOWER_BOUND` (`score >= beta`): return `score` cutoff
  - `UPPER_BOUND` (`score <= alpha`): return `score` cutoff

### Step 3: Tests & Harness
- Add unit test `testTTDoesNotChangeBestMove` in `SearchEngineTest.java`.
- Run `./mvnw clean test` and verify all tests pass.

## Verification Command
```bash
./mvnw clean test -Dtest=SearchEngineTest,SearchEngineUpgradeTest
```
