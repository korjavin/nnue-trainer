"""Export a trained NNUE v2 model to a Java-loadable weights JSON + metadata.

The runtime (bead d4a.1.3, Java opt-in eval) needs the raw float weights, not a
PyTorch checkpoint. This writes:

  * a large weights blob (`--out-weights`, gitignored) holding both EmbeddingBag
    matrices [num_patterns x W] for STM and NSTM and the three dense layers
    (l1 2062->16, l2 16->32, l3 32->1) with weights AND biases, and
  * a small committed metadata JSON (`--out-meta`) describing shapes, layer
    order, activation, and the regen command.

Weight tensors are emitted as plain nested JSON float lists (row-major, the same
[out, in] convention PyTorch's nn.Linear uses) so any language can load them with
a stock JSON parser and no framework. Deterministic output (sort_keys). Reuses
NNUEv2/read_num_patterns from train_v2; no new deps.
"""
import argparse
import json
import os
import sys

import torch

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
# allow `python3 python/v2/export_weights.py` (script dir, not repo root, on sys.path)
if _REPO_ROOT not in sys.path:
    sys.path.insert(0, _REPO_ROOT)

from python.v2.train_v2 import NNUEv2, read_num_patterns, _DEFAULT_DICT

_HERE = os.path.dirname(os.path.abspath(__file__))
_DEFAULT_MODEL = os.path.join(_HERE, "nnue_v2_model.pt")
_DEFAULT_WEIGHTS = os.path.join(_HERE, "nnue_v2_weights.json")
_DEFAULT_META = os.path.join(_HERE, "nnue_v2_weights_meta.json")

_REGEN = (
    "python3 python/v2/export_weights.py "
    "--model python/v2/nnue_v2_model.pt --dict python/v2/nnue_v2_dictionary.json"
)


def load_model(model_path, dict_path, width):
    """Rebuild an NNUEv2 sized from the dictionary and load its state_dict."""
    num_patterns = read_num_patterns(dict_path)
    model = NNUEv2(num_patterns, W=width)
    # weights_only=True: the file is a plain state_dict (train_v2 saves model.state_dict()), so
    # never unpickle arbitrary objects out of a --model path.
    model.load_state_dict(torch.load(model_path, map_location="cpu", weights_only=True))
    model.eval()
    return model


def model_to_weights(model):
    """Serialisable dict of every weight/bias tensor as nested float lists."""
    def w(t):
        return t.detach().cpu().tolist()

    return {
        # EmbeddingBag tables: [num_patterns x W], one row per pattern id.
        "stm_embed": w(model.stm_embed.weight),
        "nstm_embed": w(model.nstm_embed.weight),
        # Dense stack; nn.Linear weight is [out, in], bias is [out].
        "l1": {"weight": w(model.l1.weight), "bias": w(model.l1.bias)},
        "l2": {"weight": w(model.l2.weight), "bias": w(model.l2.bias)},
        "l3": {"weight": w(model.l3.weight), "bias": w(model.l3.bias)},
    }


def weights_metadata(model):
    """Small committed description a Java loader reads before the big blob."""
    return {
        "format": "nnue-v2-weights-json/1",
        "W": model.W,
        "num_patterns": model.num_patterns,
        "dense_size": model.dense_size,
        "concat_order": ["stm_acc(W)", "nstm_acc(W)", "dense(dense_size)"],
        "concat_dim": 2 * model.W + model.dense_size,
        "activation": "relu",
        "layers": {
            "l1": {"in": 2 * model.W + model.dense_size, "out": 16},
            "l2": {"in": 16, "out": 32},
            "l3": {"in": 32, "out": 1},
        },
        "embeddings": {
            "stm_embed": [model.num_patterns, model.W],
            "nstm_embed": [model.num_patterns, model.W],
            "mode": "sum",
            "note": "counted pattern ids -> sum of rows == STM/NSTM accumulator",
        },
        "regen": _REGEN,
    }


def main(argv=None):
    p = argparse.ArgumentParser(description="Export NNUE v2 weights to JSON.")
    p.add_argument("--model", default=_DEFAULT_MODEL)
    p.add_argument("--dict", dest="dict_path", default=_DEFAULT_DICT)
    p.add_argument("--width", type=int, default=1024)
    p.add_argument("--out-weights", default=_DEFAULT_WEIGHTS)
    p.add_argument("--out-meta", default=_DEFAULT_META)
    args = p.parse_args(argv)

    model = load_model(args.model, args.dict_path, args.width)

    with open(args.out_weights, "w") as f:
        json.dump(model_to_weights(model), f, sort_keys=True)
        f.write("\n")

    meta = weights_metadata(model)
    with open(args.out_meta, "w") as f:
        json.dump(meta, f, sort_keys=True, indent=2)
        f.write("\n")

    size_mb = os.path.getsize(args.out_weights) / 1e6
    print("model:        %s" % args.model)
    print("num_patterns: %d  W: %d" % (model.num_patterns, model.W))
    print("weights:      %s (%.1f MB)" % (args.out_weights, size_mb))
    print("meta:         %s" % args.out_meta)
    return meta


if __name__ == "__main__":
    main()
