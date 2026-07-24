import torch
import torch.nn as nn
import json

class NNUEv2(nn.Module):
    def __init__(self, num_patterns, k=1024, dense_size=14, hidden_size=32):
        super(NNUEv2, self).__init__()
        self.num_patterns = num_patterns
        self.k = k
        self.dense_size = dense_size
        self.hidden_size = hidden_size

        # We use EmbeddingBag with sum mode for accumulators
        self.accumulator = nn.EmbeddingBag(num_patterns, k, mode='sum')

        # Dense layers
        in_features = k * 2 + dense_size
        self.layer1 = nn.Linear(in_features, hidden_size)
        self.relu = nn.ReLU()
        self.layer2 = nn.Linear(hidden_size, 1)
        self.sigmoid = nn.Sigmoid()

    def forward(self, sparse_stm, sparse_nstm, dense14, stm_offsets=None, nstm_offsets=None):
        if stm_offsets is not None:
            acc_stm = self.accumulator(sparse_stm, offsets=stm_offsets)
        else:
            acc_stm = self.accumulator(sparse_stm)

        acc_stm = self.relu(acc_stm)

        if nstm_offsets is not None:
            acc_nstm = self.accumulator(sparse_nstm, offsets=nstm_offsets)
        else:
            acc_nstm = self.accumulator(sparse_nstm)

        acc_nstm = self.relu(acc_nstm)

        x = torch.cat([acc_stm, acc_nstm, dense14], dim=1)

        x = self.layer1(x)
        x = self.relu(x)
        x = self.layer2(x)
        x = self.sigmoid(x)

        return x

    def export_to_json(self, filepath="nnue_v2_weights.json"):
        """
        Save weights and architecture config.
        """
        state = self.state_dict()

        export_data = {
            "config": {
                "num_patterns": self.num_patterns,
                "k": self.k,
                "dense_size": self.dense_size,
                "hidden_size": self.hidden_size
            },
            "weights": {
                "accumulator.weight": state["accumulator.weight"].tolist(),
                "layer1.weight": state["layer1.weight"].tolist(),
                "layer1.bias": state["layer1.bias"].tolist(),
                "layer2.weight": state["layer2.weight"].tolist(),
                "layer2.bias": state["layer2.bias"].tolist()
            }
        }

        with open(filepath, "w") as f:
            json.dump(export_data, f)
