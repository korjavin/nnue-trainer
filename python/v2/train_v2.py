"""Float-first PyTorch NNUE v2 reference model + deterministic training.

Two-accumulator (Stockfish-style) structure: separate STM and NSTM
`EmbeddingBag(num_patterns, W, mode='sum')` fed COUNTED pattern ids as
per-sample weights, concatenated with 14 dense features, through a small
dense stack to a single WDL scalar. Float-only prototype (no quantization).

See docs/plans/20260724-v2-pytorch-two-accumulator-model.md.
"""
import json
import math
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
        try:
            from python.v2 import extract_examples
        except ImportError:  # run as a script: python/v2 is on sys.path directly
            import extract_examples
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


def split_indices(n, val_frac, gen):
    """Deterministic held-out split -> (train_idx, val_idx), ascending order.

    Single source of truth for train_model and validators. Advances `gen` by
    one randperm(n) so callers reusing the generator (e.g. epoch shuffles) keep
    their downstream RNG state unchanged.
    """
    perm = torch.randperm(n, generator=gen).tolist()
    n_val = int(round(n * val_frac))
    val_set = set(perm[:n_val])
    train_idx = [i for i in range(n) if i not in val_set]
    val_idx = [i for i in range(n) if i in val_set]
    return train_idx, val_idx


def forward_batch(model, batch):
    """Run a collate() batch through the model (unpacks the fixed tensor keys)."""
    return model(batch["stm_ids"], batch["stm_off"], batch["stm_w"],
                 batch["nstm_ids"], batch["nstm_off"], batch["nstm_w"],
                 batch["dense"])


def train_model(examples, num_patterns, W=1024, epochs=20, batch_size=64,
                lr=1e-3, seed=0, val_frac=0.2, on_epoch=None):
    """Deterministic float training; returns (model, train_mse, val_mse).

    If `on_epoch` is given, it is called `on_epoch(epoch_index, train_mse,
    val_mse)` after each epoch. When None, no per-epoch MSE is computed.
    """
    set_seed(seed)
    if batch_size < 1:
        raise ValueError(f"batch_size must be >= 1, got {batch_size}")
    gen = torch.Generator().manual_seed(seed)
    train_i, val_i = split_indices(len(examples), val_frac, gen)
    train_ex = [examples[i] for i in train_i]
    val_ex = [examples[i] for i in val_i]
    # A random model would otherwise be saved as if trained.
    if not train_ex:
        raise ValueError(
            f"empty training split (examples={n}, val_frac={val_frac}); "
            "need at least one training example")

    model = NNUEv2(num_patterns, W=W)
    opt = torch.optim.Adam(model.parameters(), lr=lr)
    loss_fn = nn.MSELoss()

    for epoch in range(epochs):
        model.train()
        order = torch.randperm(len(train_ex), generator=gen).tolist()
        for start in range(0, len(order), batch_size):
            idx = order[start:start + batch_size]
            batch = collate([train_ex[i] for i in idx])
            opt.zero_grad()
            out = forward_batch(model, batch)
            loss = loss_fn(out, batch["wdl"])
            loss.backward()
            opt.step()
        if on_epoch is not None:
            on_epoch(epoch, _eval_mse(model, train_ex, batch_size),
                     _eval_mse(model, val_ex, batch_size))

    return (model,
            _eval_mse(model, train_ex, batch_size),
            _eval_mse(model, val_ex, batch_size))


def _eval_mse(model, examples, batch_size=1024):
    """MSE over `examples`, evaluated in mini-batches so a large corpus does
    not materialize one giant accumulator tensor (would OOM the CPU box)."""
    if not examples:
        return float("nan")
    model.eval()
    total_sq = 0.0
    with torch.no_grad():
        for start in range(0, len(examples), batch_size):
            batch = collate(examples[start:start + batch_size])
            out = forward_batch(model, batch)
            total_sq += torch.sum((out - batch["wdl"]) ** 2).item()
    return total_sq / len(examples)


def model_metadata(model):
    """Serialisable metadata describing a trained NNUEv2 model."""
    return {
        "W": model.W,
        "num_patterns": model.num_patterns,
        "dense_size": model.dense_size,
        "layers": {
            "l1": list(model.l1.weight.shape),
            "l2": list(model.l2.weight.shape),
            "l3": list(model.l3.weight.shape),
        },
    }


def main(argv=None):
    import argparse

    p = argparse.ArgumentParser(description="Train float NNUE v2 model")
    p.add_argument("--dataset", default=_DEFAULT_DATASET)
    p.add_argument("--dict", dest="dict_path", default=_DEFAULT_DICT)
    p.add_argument("--examples", default=_DEFAULT_EXAMPLES)
    p.add_argument("--regenerate", action="store_true")
    p.add_argument("--out-model", default=os.path.join(_HERE, "nnue_v2_model.pt"))
    p.add_argument("--out-meta", default=os.path.join(_HERE, "nnue_v2_model_meta.json"))
    p.add_argument("--epochs", type=int, default=20)
    p.add_argument("--batch-size", type=int, default=64)
    p.add_argument("--lr", type=float, default=1e-3)
    p.add_argument("--width", type=int, default=1024)
    p.add_argument("--seed", type=int, default=0)
    p.add_argument("--val-frac", type=float, default=0.2)
    args = p.parse_args(argv)

    num_patterns = read_num_patterns(args.dict_path)
    examples = load_examples(args.examples, args.dataset, args.dict_path,
                             regenerate=args.regenerate)
    model, train_mse, val_mse = train_model(
        examples, num_patterns, W=args.width, epochs=args.epochs,
        batch_size=args.batch_size, lr=args.lr, seed=args.seed,
        val_frac=args.val_frac)

    torch.save(model.state_dict(), args.out_model)
    meta = model_metadata(model)
    # A NaN metric (empty train/val split on a tiny corpus) is not valid JSON;
    # write null so cross-language consumers can still parse the metadata.
    meta.update({"train_mse": train_mse if math.isfinite(train_mse) else None,
                 "val_mse": val_mse if math.isfinite(val_mse) else None,
                 "num_examples": len(examples)})
    with open(args.out_meta, "w") as f:
        json.dump(meta, f, indent=2, allow_nan=False)

    print(f"num_patterns={num_patterns} examples={len(examples)} "
          f"train_mse={train_mse:.6f} val_mse={val_mse:.6f}")
    print(f"saved model -> {args.out_model}")
    print(f"saved meta  -> {args.out_meta}")
    return train_mse, val_mse


if __name__ == "__main__":
    main()
