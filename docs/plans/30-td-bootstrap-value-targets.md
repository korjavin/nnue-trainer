# Implementation Plan - Lower Value-Target Label Noise via TD-Bootstrapped Targets (nnue-trainer-ntd.7)

## Issue
`nnue-trainer-ntd.7`: Lower value-target label noise (search/TD-bootstrapped targets) - top training lever.

## Problem Context
Held-out validation MSE for NNUE training currently floors at ~0.76 when using pure `+1` / `-1` final game outcome labels. Early and mid-game positions labeled strictly by final game outcomes carry high variance/noise. Standard AlphaZero and NNUE pipelines reduce label noise by blending the final outcome with bootstrapped search/TD evaluations.

## Design Goals
1. Support `--label-mode [outcome|td_leaf|discounted]` in `import_games.py` and `GameImporter.java`.
2. In `td_leaf` mode:
   - Calculate position label as `(1 - lambda) * search_eval + lambda * final_outcome` (default `lambda = 0.5`).
   - For positions near game end, weight final outcome higher (`lambda` decays with turn distance).
3. In `discounted` mode:
   - Scale target outcome by decay factor `gamma^(total_turns - turn_index)` (e.g. `gamma = 0.98`).
4. Ensure both Python `import_games.py` and Java `GameImporter.java` output datasets with the updated `eval` float label field.
5. Provide unit test coverage in `GameImporterTest.java` verifying label computation modes.

## Step-by-Step Implementation

### Step 1: Update `import_games.py`
- Add `--label-mode` CLI argument (`outcome`, `td_leaf`, `discounted`).
- Implement `--lambda-val` (default `0.5`) and `--gamma-val` (default `0.98`).
- Update position evaluation calculation during game JSON export.

### Step 2: Update `GameImporter.java`
- Add `LabelMode` enum (`OUTCOME`, `TD_LEAF`, `DISCOUNTED`).
- Update `convertGameToTrainingRecords` to compute target evaluations according to the configured `LabelMode`.

### Step 3: Tests
- Add unit tests in `GameImporterTest.java` testing `TD_LEAF` and `DISCOUNTED` label computation modes.

## Verification Command
```bash
./mvnw clean test
python3 -m unittest discover -s python -p "*_test.py"
```
