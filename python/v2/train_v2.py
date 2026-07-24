"""Float-first PyTorch NNUE v2 reference model + deterministic training.

Two-accumulator (Stockfish-style) structure: separate STM and NSTM
`EmbeddingBag(num_patterns, W, mode='sum')` fed COUNTED pattern ids as
per-sample weights, concatenated with 14 dense features, through a small
dense stack to a single WDL scalar. Float-only prototype (no quantization).

See docs/plans/20260724-v2-pytorch-two-accumulator-model.md.
"""
import json
import os
import random

import numpy as np
import torch
import torch.nn as nn

_HERE = os.path.dirname(os.path.abspath(__file__))
_REPO_ROOT = os.path.abspath(os.path.join(_HERE, "..", ".."))
_DEFAULT_DICT = os.path.join(_HERE, "nnue_v2_dictionary.json")
_DEFAULT_EXAMPLES = os.path.join(_HERE, "nnue_v2_examples.jsonl")
_DEFAULT_DATASET = os.path.join(_REPO_ROOT, "dataset.json")


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


def read_num_patterns(dict_path=_DEFAULT_DICT):
    """Read `metadata.num_patterns` from the dictionary JSON (never hardcoded)."""
    with open(dict_path) as f:
        meta = json.load(f)["metadata"]
    assert "num_patterns" in meta, "dictionary metadata missing num_patterns"
    return int(meta["num_patterns"])


def load_examples(path=_DEFAULT_EXAMPLES, dataset_path=_DEFAULT_DATASET,
                  dict_path=_DEFAULT_DICT, regenerate=False):
    """Load examples from the gitignored JSONL, regenerating it if needed.

    Regenerates via `extract_examples.main` when the file is missing or
    `regenerate=True`, then reads it back line by line.
    """
    if regenerate or not os.path.exists(path):
        from python.v2 import extract_examples
        extract_examples.main(
            ["--dataset", dataset_path, "--dict", dict_path, "--out", path]
        )
    examples = []
    with open(path) as f:
        for line in f:
            line = line.strip()
            if line:
                examples.append(json.loads(line))
    return examples


def set_seed(seed):
    """Seed python `random`, numpy, and torch for deterministic training."""
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)


def train(examples, num_patterns, W=1024, epochs=20, batch_size=64, lr=1e-3,
          seed=0, val_frac=0.2):
    """Deterministic float training; returns (train_mse, val_mse)."""
    set_seed(seed)
    gen = torch.Generator().manual_seed(seed)
    n = len(examples)
    perm = torch.randperm(n, generator=gen).tolist()
    n_val = int(round(n * val_frac))
    val_idx = set(perm[:n_val])
    train_ex = [examples[i] for i in range(n) if i not in val_idx]
    val_ex = [examples[i] for i in range(n) if i in val_idx]

    model = NNUEv2(num_patterns, W=W)
    opt = torch.optim.Adam(model.parameters(), lr=lr)
    loss_fn = nn.MSELoss()

    for _ in range(epochs):
        model.train()
        order = torch.randperm(len(train_ex), generator=gen).tolist()
        for start in range(0, len(order), batch_size):
            idx = order[start:start + batch_size]
            batch = collate([train_ex[i] for i in idx])
            opt.zero_grad()
            out = model(batch["stm_ids"], batch["stm_off"], batch["stm_w"],
                        batch["nstm_ids"], batch["nstm_off"], batch["nstm_w"],
                        batch["dense"])
            loss = loss_fn(out, batch["wdl"])
            loss.backward()
            opt.step()

    return _eval_mse(model, train_ex, loss_fn), _eval_mse(model, val_ex, loss_fn)


def _eval_mse(model, examples, loss_fn):
    if not examples:
        return float("nan")
    model.eval()
    with torch.no_grad():
        batch = collate(examples)
        out = model(batch["stm_ids"], batch["stm_off"], batch["stm_w"],
                    batch["nstm_ids"], batch["nstm_off"], batch["nstm_w"],
                    batch["dense"])
        return loss_fn(out, batch["wdl"]).item()
