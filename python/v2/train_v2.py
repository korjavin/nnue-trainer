"""Float-first PyTorch NNUE v2 reference model + deterministic training.

Two-accumulator (Stockfish-style) structure: separate STM and NSTM
`EmbeddingBag(num_patterns, W, mode='sum')` fed COUNTED pattern ids as
per-sample weights, concatenated with 14 dense features, through a small
dense stack to a single WDL scalar. Float-only prototype (no quantization).

See docs/plans/20260724-v2-pytorch-two-accumulator-model.md.
"""
import torch
import torch.nn as nn


class NNUEv2(nn.Module):
    """Two-accumulator NNUE v2 model (float reference)."""

    def __init__(self, num_patterns, W=1024, dense_size=14):
        super().__init__()
        self.num_patterns = num_patterns
        self.W = W
        self.dense_size = dense_size
        self.stm_embed = nn.EmbeddingBag(num_patterns, W, mode="sum")
        self.nstm_embed = nn.EmbeddingBag(num_patterns, W, mode="sum")
        self.l1 = nn.Linear(2 * W + dense_size, 16)
        self.l2 = nn.Linear(16, 32)
        self.l3 = nn.Linear(32, 1)

    def forward(self, stm_ids, stm_off, stm_w, nstm_ids, nstm_off, nstm_w, dense):
        acc_stm = self.stm_embed(stm_ids, stm_off, per_sample_weights=stm_w)
        acc_nstm = self.nstm_embed(nstm_ids, nstm_off, per_sample_weights=nstm_w)
        x = torch.cat([acc_stm, acc_nstm, dense], dim=1)
        x = torch.relu(self.l1(x))
        x = torch.relu(self.l2(x))
        return self.l3(x).squeeze(1)


def _bag_tensors(counts_list):
    """Flatten a list of {str(id): count} dicts into EmbeddingBag inputs.

    Returns (ids, offsets, per_sample_weights). Empty dicts produce empty bags
    via consecutive equal offsets -> a zero accumulator row (no crash).
    """
    ids, weights, offsets = [], [], []
    pos = 0
    for counts in counts_list:
        offsets.append(pos)
        for k, v in counts.items():
            ids.append(int(k))
            weights.append(float(v))
            pos += 1
    return (
        torch.tensor(ids, dtype=torch.long),
        torch.tensor(offsets, dtype=torch.long),
        torch.tensor(weights, dtype=torch.float),
    )


def collate(examples):
    """Turn a list of example dicts into model input tensors + wdl target.

    Returns a dict with keys: stm_ids, stm_off, stm_w, nstm_ids, nstm_off,
    nstm_w, dense, wdl.
    """
    stm_ids, stm_off, stm_w = _bag_tensors([e["stm_pattern_counts"] for e in examples])
    nstm_ids, nstm_off, nstm_w = _bag_tensors([e["nstm_pattern_counts"] for e in examples])
    dense = torch.tensor([e["dense"] for e in examples], dtype=torch.float)
    wdl = torch.tensor([e["wdl"] for e in examples], dtype=torch.float)
    return {
        "stm_ids": stm_ids, "stm_off": stm_off, "stm_w": stm_w,
        "nstm_ids": nstm_ids, "nstm_off": nstm_off, "nstm_w": nstm_w,
        "dense": dense, "wdl": wdl,
    }
