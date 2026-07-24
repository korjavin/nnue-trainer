import torch
import torch.nn as nn
import torch.optim as optim
from torch.utils.data import Dataset, DataLoader
import json
import argparse
from nnue_v2_model import NNUEv2

class SyntheticDataset(Dataset):
    def __init__(self, vocab_size, num_samples):
        self.vocab_size = vocab_size
        self.num_samples = num_samples

    def __len__(self):
        return self.num_samples

    def __getitem__(self, idx):
        # Return arbitrary sized sequences for stm and nstm, dense features and a target
        seq_len_stm = torch.randint(1, 20, (1,)).item()
        seq_len_nstm = torch.randint(1, 20, (1,)).item()

        stm_indices = torch.randint(0, self.vocab_size, (seq_len_stm,))
        nstm_indices = torch.randint(0, self.vocab_size, (seq_len_nstm,))
        dense_features = torch.randn(14)

        # Binary target (0 or 1) for BCEWithLogitsLoss
        target = torch.randint(0, 2, (1,)).float()

        return stm_indices, nstm_indices, dense_features, target

def collate_fn(batch):
    stm_indices_list = []
    stm_offsets_list = []
    nstm_indices_list = []
    nstm_offsets_list = []
    dense_features_list = []
    targets_list = []

    current_stm_offset = 0
    current_nstm_offset = 0

    for stm_indices, nstm_indices, dense_features, target in batch:
        stm_indices_list.append(stm_indices)
        stm_offsets_list.append(current_stm_offset)
        current_stm_offset += len(stm_indices)

        nstm_indices_list.append(nstm_indices)
        nstm_offsets_list.append(current_nstm_offset)
        current_nstm_offset += len(nstm_indices)

        dense_features_list.append(dense_features)
        targets_list.append(target)

    stm_indices_tensor = torch.cat(stm_indices_list)
    stm_offsets_tensor = torch.tensor(stm_offsets_list, dtype=torch.long)
    nstm_indices_tensor = torch.cat(nstm_indices_list)
    nstm_offsets_tensor = torch.tensor(nstm_offsets_list, dtype=torch.long)
    dense_features_tensor = torch.stack(dense_features_list)
    targets_tensor = torch.stack(targets_list)

    return stm_indices_tensor, stm_offsets_tensor, nstm_indices_tensor, nstm_offsets_tensor, dense_features_tensor, targets_tensor

def train(epochs=5, batch_size=32, vocab_size=1000, num_samples=1000, export_path="nnue_v2_weights.json"):
    dataset = SyntheticDataset(vocab_size=vocab_size, num_samples=num_samples)
    dataloader = DataLoader(dataset, batch_size=batch_size, collate_fn=collate_fn, shuffle=True)

    model = NNUEv2(vocab_size=vocab_size)
    criterion = nn.BCEWithLogitsLoss()
    optimizer = optim.Adam(model.parameters(), lr=1e-3)

    for epoch in range(epochs):
        model.train()
        total_loss = 0
        for stm_indices, stm_offsets, nstm_indices, nstm_offsets, dense_features, targets in dataloader:
            optimizer.zero_grad()
            out = model(stm_indices, stm_offsets, nstm_indices, nstm_offsets, dense_features)
            loss = criterion(out, targets)
            loss.backward()
            optimizer.step()
            total_loss += loss.item()

        print(f"Epoch {epoch+1}/{epochs} - Loss: {total_loss/len(dataloader):.4f}")

    model.export_weights(export_path)
    print(f"Model weights exported to {export_path}")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Train NNUE v2 model")
    parser.add_argument("--epochs", type=int, default=5, help="Number of training epochs")
    parser.add_argument("--batch_size", type=int, default=32, help="Batch size")
    parser.add_argument("--vocab_size", type=int, default=1000, help="Vocabulary size (max pattern ID + 1)")
    parser.add_argument("--num_samples", type=int, default=1000, help="Number of synthetic samples")
    parser.add_argument("--export_path", type=str, default="nnue_v2_weights.json", help="Path to export weights")

    args = parser.parse_args()

    train(epochs=args.epochs, batch_size=args.batch_size, vocab_size=args.vocab_size, num_samples=args.num_samples, export_path=args.export_path)
