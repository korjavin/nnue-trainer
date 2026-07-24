import torch
import torch.nn as nn
import json

class NNUEv2(nn.Module):
    def __init__(self, vocab_size: int, embedding_dim: int = 1024, dense_dim: int = 14, hidden_dim: int = 32):
        super().__init__()
        self.vocab_size = vocab_size
        self.embedding_dim = embedding_dim
        self.dense_dim = dense_dim
        self.hidden_dim = hidden_dim

        # Embedding bag for sparse pattern IDs. mode='sum' acts as the accumulator.
        self.embedding = nn.EmbeddingBag(vocab_size, embedding_dim, mode='sum')

        # Accumulator activation
        self.acc_relu = nn.ReLU()

        # Dense hidden layers
        concat_dim = embedding_dim * 2 + dense_dim
        self.fc1 = nn.Linear(concat_dim, hidden_dim)
        self.fc1_relu = nn.ReLU()
        self.fc2 = nn.Linear(hidden_dim, 1)

    def forward(self, stm_indices, stm_offsets, nstm_indices, nstm_offsets, dense_features):
        """
        Forward pass for the PyTorch NNUE v2 model.

        Args:
            stm_indices (Tensor): 1D tensor of pattern IDs for STM.
            stm_offsets (Tensor): 1D tensor of start offsets for each sequence in the STM batch.
            nstm_indices (Tensor): 1D tensor of pattern IDs for NSTM.
            nstm_offsets (Tensor): 1D tensor of start offsets for each sequence in the NSTM batch.
            dense_features (Tensor): 2D tensor of shape (batch_size, 14) for dense manual floats.

        Returns:
            Tensor: Output logits of shape (batch_size, 1).
        """
        # First Layer (Accumulators)
        acc_stm = self.embedding(stm_indices, stm_offsets)
        acc_stm = self.acc_relu(acc_stm)

        acc_nstm = self.embedding(nstm_indices, nstm_offsets)
        acc_nstm = self.acc_relu(acc_nstm)

        # Concatenate [Acc_STM, Acc_NSTM, Dense14]
        x = torch.cat([acc_stm, acc_nstm, dense_features], dim=1)

        # Dense Hidden Layers
        x = self.fc1(x)
        x = self.fc1_relu(x)
        out = self.fc2(x)

        return out

    def export_weights(self, filepath: str):
        """
        Exports the model weights and architecture configuration to a JSON file.
        Format compatible with the Java NNUE inference engine.
        """
        state_dict = self.state_dict()

        # We might need to transpose weights for Java compatibility if required, but assuming standard layout
        export_data = {
            "vocab_size": self.vocab_size,
            "embedding_dim": self.embedding_dim,
            "dense_dim": self.dense_dim,
            "hidden_dim": self.hidden_dim,
            "weights": {
                "embedding.weight": state_dict["embedding.weight"].tolist(),
                "fc1.weight": state_dict["fc1.weight"].tolist(),
                "fc1.bias": state_dict["fc1.bias"].tolist(),
                "fc2.weight": state_dict["fc2.weight"].tolist(),
                "fc2.bias": state_dict["fc2.bias"].tolist()
            }
        }

        with open(filepath, 'w') as f:
            json.dump(export_data, f)
