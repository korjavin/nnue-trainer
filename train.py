import json
import random
import math
import os

class NNUE:
    def __init__(self, input_size=864, hidden_size=256):
        # Xavier initialization
        scale = math.sqrt(2.0 / input_size)
        self.w1 = [[random.gauss(0, scale) for _ in range(input_size)] for _ in range(hidden_size)]
        self.b1 = [0.0] * hidden_size
        self.w2 = [random.gauss(0, math.sqrt(2.0 / hidden_size)) for _ in range(hidden_size)]
        self.b2 = 0.0

    def forward(self, x):
        h_sum = []
        h_act = []
        for i in range(len(self.w1)):
            s = self.b1[i]
            for j in range(len(x)):
                if x[j] != 0.0:  # Sparse optimization: input is mostly zeros
                    s += self.w1[i][j] * x[j]
            h_sum.append(s)
            # Clipped ReLU [0.0, 127.0]
            h_act.append(max(0.0, min(127.0, s)))

        out = self.b2
        for i in range(len(self.w2)):
            out += self.w2[i] * h_act[i]

        return out, h_sum, h_act

    def backward(self, x, target, out, h_sum, h_act, lr=0.001):
        # dLoss/dout = 2 * (out - target)
        error = out - target

        # Output layer gradients
        dw2 = [error * h for h in h_act]
        db2 = error

        # Hidden layer gradients
        dw1 = []
        db1 = []
        for i in range(len(self.w1)):
            # Derivative of Clipped ReLU
            if 0.0 <= h_sum[i] <= 127.0:
                dh = error * self.w2[i]
            else:
                dh = 0.0
            
            dw1.append([dh * xi for xi in x])
            db1.append(dh)

        # SGD Update step
        for i in range(len(self.w1)):
            self.b1[i] -= lr * db1[i]
            for j in range(len(x)):
                if x[j] != 0.0:  # Sparse update
                    self.w1[i][j] -= lr * dw1[i][j]

        for i in range(len(self.w2)):
            self.w2[i] -= lr * dw2[i]
        self.b2 -= lr * db2

def train():
    dataset_path = "/Users/iv/Projects/nnue-trainer/dataset.json"
    if not os.path.exists(dataset_path):
        print(f"Error: dataset.json not found at {dataset_path}")
        return

    with open(dataset_path, "r") as f:
        dataset = json.load(f)

    print(f"Loaded {len(dataset)} position records.")
    
    # Initialize model
    model = NNUE(input_size=864, hidden_size=256)
    
    epochs = 40
    batch_size = 16
    initial_lr = 0.01

    for epoch in range(epochs):
        random.shuffle(dataset)
        total_loss = 0.0
        
        # Learning rate decay
        lr = initial_lr / (1.0 + 0.1 * epoch)

        for item in dataset:
            x = item['features']
            target = item['target']

            # Forward pass
            out, h_sum, h_act = model.forward(x)
            
            # MSE loss contribution
            loss = (out - target) ** 2
            total_loss += loss

            # Backward pass & SGD step
            model.backward(x, target, out, h_sum, h_act, lr=lr)

        avg_loss = total_loss / len(dataset)
        print(f"Epoch {epoch+1}/{epochs} - Avg Loss (MSE): {avg_loss:.5f} (lr: {lr:.5f})")

    # Export weights to resources folder
    out_dir = "/Users/iv/Projects/nnue-trainer/src/main/resources"
    os.makedirs(out_dir, exist_ok=True)
    out_path = os.path.join(out_dir, "nnue_weights.json")

    weights_data = {
        "hiddenWeights": model.w1,
        "hiddenBiases": model.b1,
        "outputWeights": model.w2,
        "outputBias": model.b2
    }

    with open(out_path, "w") as f:
        json.dump(weights_data, f)

    print(f"Successfully saved trained NNUE weights to {out_path}")

if __name__ == "__main__":
    train()
