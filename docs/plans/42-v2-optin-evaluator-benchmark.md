# Implementation Plan - Opt-in NNUE v2 Evaluator Integration & NPS Benchmark (nnue-trainer-d4a.1.3)

> **CRITICAL REQUIREMENT**: All Pull Requests for this task MUST target the `v2` branch, NOT `master`.

## Issue & Bead
Bead: `nnue-trainer-d4a.1.3` (under Epic `nnue-trainer-d4a.1` / `nnue-trainer-d4a`).

## Objective
Integrate the trained NNUE v2 evaluator behind an explicit opt-in flag (`-DUSE_NNUE_V2=true`) on the `v2` branch and build a benchmark harness measuring evaluation and search throughput (Nodes Per Second - NPS).

## Design Specifications
1. **Opt-in Evaluator Path (`NNUEv2Evaluator.java`)**:
   - Provide `NNUEv2Evaluator` implements/plugs into `SearchEngine` evaluation when system property `-DUSE_NNUE_V2=true` is set.
   - Fall back to standard NNUE v1 or static evaluation when false.
2. **NPS Benchmark Harness (`BenchmarkV2Throughput.java`)**:
   - Measure evaluations per second and search nodes per second across 1,000 board evaluations on 5x5, 8x8, and 12x12 board sizes.
   - Print benchmark summary report.

## Files to Create
- `src/main/java/com/engine/nnue_trainer/v2/NNUEv2Evaluator.java`: Opt-in evaluator implementation.
- `src/main/java/com/engine/nnue_trainer/v2/BenchmarkV2Throughput.java`: Benchmark executable.
- `src/test/java/com/engine/nnue_trainer/v2/NNUEv2EvaluatorTest.java`: Unit tests verifying opt-in flag toggle behavior and fallback evaluation.

## Verification Command
```bash
./mvnw test -Dtest=NNUEv2EvaluatorTest
```
