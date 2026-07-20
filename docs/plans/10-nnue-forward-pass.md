# NNUE Forward Pass Inference

Implement the forward pass layers of the NNUE model in `com.engine.nnue_trainer.nnue`.

## Tasks

- [ ] Task 1: Implement feed-forward NNUE model in Java

### Task 1: Implement feed-forward NNUE model in Java
1. Read model weights/biases from a file or load static default arrays.
2. Implement forward pass:
   - Input layer: 104 floats.
   - Hidden Layer (with Clipped ReLU activation function: `max(0, min(127, x))`).
   - Output layer: scalar float score.
3. Verify inference works correctly and produces evaluation scores.\n