import torch
import torch.nn as nn
import torch.optim as optim
from torch.utils.data import Dataset, DataLoader
import argparse
from python.v2.nnue_v2_model import NNUEv2
import os

class DummyNNUEDataset(Dataset):
    def __init__(self, num_samples=1000, num_patterns=100, max_patterns_per_board=30):
        self.num_samples = num_samples
        self.num_patterns = num_patterns
        self.max_patterns_per_board = max_patterns_per_board

        # In a real scenario, these would be lists of lists of variable lengths
        # and we would use collate_fn to pad them and create offsets.
        # For simplicity in this mock, we assume padded max_patterns_per_board sequences
        self.sparse_stm = torch.randint(0, num_patterns, (num_samples, max_patterns_per_board))
        self.sparse_nstm = torch.randint(0, num_patterns, (num_samples, max_patterns_per_board))

        self.labels = torch.rand((num_samples, 1))
        self.dense14 = torch.rand((num_samples, 14))

    def __len__(self):
        return self.num_samples

    def __getitem__(self, idx):
        return self.sparse_stm[idx], self.sparse_nstm[idx], self.dense14[idx], self.labels[idx]

def wdl_loss(pred, target):
    """
    Win-Draw-Loss loss against targets scaled to [0, 1].
    (win=1.0, draw=0.5, loss=0.0). Since our network ends with a Sigmoid, we use BCELoss.
    """
    criterion = nn.BCELoss()
    return criterion(pred, target)

def train(model, dataloader, optimizer, epochs=5):
    model.train()
    for epoch in range(epochs):
        total_loss = 0.0
        for batch_stm, batch_nstm, batch_dense, batch_labels in dataloader:
            optimizer.zero_grad()

            # Since inputs are padded to 2D tensors of shape (batch, seq),
            # EmbeddingBag defaults to reducing across dimension 1 when no offsets are provided.
            preds = model(batch_stm, batch_nstm, batch_dense)

            loss = wdl_loss(preds, batch_labels)

            loss.backward()
            optimizer.step()

            total_loss += loss.item()

        avg_loss = total_loss / len(dataloader)
        print(f"Epoch {epoch+1}/{epochs}, Loss: {avg_loss:.4f}")

def main():
    parser = argparse.ArgumentParser(description="Train PyTorch Two-Accumulator Model for NNUE v2")
    parser.add_argument("--epochs", type=int, default=10, help="Number of training epochs")
    parser.add_argument("--batch_size", type=int, default=32, help="Batch size")
    parser.add_argument("--num_patterns", type=int, default=5000, help="Number of distinct patterns")
    parser.add_argument("--export_path", type=str, default="nnue_v2_weights.json", help="Path to export weights")
    args = parser.parse_args()

    print(f"Initializing NNUE v2 with {args.num_patterns} patterns...")
    model = NNUEv2(num_patterns=args.num_patterns)

    print("Creating dummy dataset...")
    dataset = DummyNNUEDataset(num_samples=1000, num_patterns=args.num_patterns)
    dataloader = DataLoader(dataset, batch_size=args.batch_size, shuffle=True)

    optimizer = optim.Adam(model.parameters(), lr=0.001)

    print("Starting training...")
    train(model, dataloader, optimizer, epochs=args.epochs)

    print(f"Exporting model to {args.export_path}...")
    model.export_to_json(args.export_path)
    print("Done!")

if __name__ == "__main__":
    main()
