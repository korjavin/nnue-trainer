# Plan: Java NNUE Trainer, Game Importer, and Periodic Retraining Loop

This plan details the implementation of an in-Java training pipeline for the NNUE value net and an automated retraining loop, replacing the Python training scripts (`train.py` and `import_games.py`) to reduce language/model drift risk and enable self-improving bots.

Refer to Beads `nnue-trainer-ntd.5` and its children (`nnue-trainer-ntd.5.1`, `nnue-trainer-ntd.5.2`, and `nnue-trainer-ntd.5.3`) for full task context and requirements.

---

## 1. Goal
Consolidate the NNUE training pipeline into pure Java, utilizing existing board rules and feature mapping classes. Introduce an automated retraining worker that periodically runs on accumulated game history, performs eval-gated promotion matches, and hot-swaps weights.

---

## 2. Requirements

### 2.1 Java NNUETrainer (`nnue-trainer-ntd.5.1`)
Create `src/main/java/com/engine/nnue_trainer/nnue/NNUETrainer.java`:
- **Network Architecture**: 864 inputs -> 256 hidden (Clipped ReLU) -> 1 output.
- **Initialization**:
  - Weight layer 1: Gaussian distribution with mean `0.0`, standard deviation `sqrt(2.0 / 864)`.
  - Hidden biases: Initialized to `0.0`.
  - Weight layer 2: Gaussian distribution with mean `0.0`, standard deviation `sqrt(2.0 / 256)`.
  - Output bias: Initialized to `0.0`.
- **Training Parameters**:
  - Epochs: 40
  - Batch size: 256
  - Initial Learning Rate: 0.01
  - LR Decay: `INITIAL_LR / (1.0 + 0.1 * epoch)`
- **Loss**: Mean Squared Error (MSE).
- **Optimization**: Mini-batch SGD. Implement both forward pass (reusing `NNUEModel.forward`) and backward pass (calculating gradients, clipped ReLU masks, and output weight updates).
- **Seedable**: Must accept a seed (e.g., using `java.util.Random`) to ensure deterministic runs.
- **Exporting**: Save the final weights to `nnue_weights.json` in the exact format expected by `NNUEModel.createDefault()` (i.e. contains keys `hiddenWeights`, `hiddenBiases`, `outputWeights`, `outputBias`).
- **Tests**: Create `src/test/java/com/engine/nnue_trainer/nnue/NNUETrainerTest.java` verifying that loss decreases over epochs and the exported weights load cleanly.

### 2.2 Java Game Importer (`nnue-trainer-ntd.5.2`)
Create `src/main/java/com/engine/nnue_trainer/train/GameImporter.java`:
- Replay completed games from SQLite (default path `../virusgame/backend/data/games.db`).
- Replay turns using the game board rules (`Board`, `SearchEngine.applyAction`) to avoid feature encoding drift.
- For each turn, generate training features via `BoardFeatureMapper.map(board, activePlayer)` and labels (`+1.0` if `activePlayer` won, `-1.0` if they lost).
- Save generated data to `dataset.json` or keep in memory.
- **Tests**: Create a parity unit test demonstrating that the board reconstruction matches the Python version exactly for a target game.

### 2.3 Periodic Retraining & Eval Gate (`nnue-trainer-ntd.5.3`)
Wire the retraining pipeline into the bot:
- **Collector**: Gather online completed game histories.
- **Retrain Trigger**: Trigger a retraining run using a `ScheduledExecutorService` (e.g. nightly or every N games).
- **Regression Guard (Gauntlet)**:
  - Do not auto-promote candidate weights blindly.
  - Run sparring evaluation games between a bot loaded with the candidate weights and a bot loaded with the current-best weights (or a fixed baseline opponent).
  - Only promote/write candidate weights if they achieve a significant win-rate improvement over current-best.
- **Promotion / Rollback**: Support hot-swapping weights in `SearchEngine`'s live model or writing `nnue_weights.json` and restarting. Keep seeds/datasets versioned for easy rollbacks.

---

## 3. Verification
- All tests compile and pass.
- `./mvnw spotless:apply` runs cleanly.
