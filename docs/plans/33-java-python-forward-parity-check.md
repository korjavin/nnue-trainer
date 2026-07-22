# Implementation Plan - Verify Java vs Python NNUE Forward Parity (nnue-trainer-ntd.3)

## Issue
`nnue-trainer-ntd.3`: Train: Import Weights & Run Parameter Tuning (Java forward() vs Python model parity check).

## Problem Context
To ensure the Java `NNUEModel` accurately mirrors the trained PyTorch model (`train.py`), we need a parity validation test that verifies both implementations produce identical forward pass scores across a diverse benchmark set of positions.

## Design Goals
1. Add a test script / unit test in `src/test/java/com/engine/nnue_trainer/nnue/NNUEModelParityTest.java`.
2. Generate benchmark test feature inputs and export their expected PyTorch model outputs via Python.
3. Assert that Java `NNUEModel.forward(accumulator)` and `NNUEModel.forward(features)` match the Python model outputs within `1e-4` tolerance.

## Step-by-Step Implementation

### Step 1: Update Python Exporter
- In `python/export_test_vectors.py`, generate 20 random/valid board feature vectors and write their computed PyTorch predictions to `src/test/resources/nnue_parity_vectors.json`.

### Step 2: Create Java Parity Test
- Create `NNUEModelParityTest.java` that loads `nnue_parity_vectors.json` and asserts Java forward outputs match expected Python outputs within `1e-4`.

## Verification Command
```bash
./mvnw test -Dtest=NNUEModelParityTest
```
