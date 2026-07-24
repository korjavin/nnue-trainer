"""Extract sparse-counted NNUE v2 training examples with STM WDL labels.

For each decoded corpus position, emit sparse COUNTED pattern ids for both the
side-to-move (STM, owner 1) and not-side-to-move (NSTM, owner 2) perspectives
using the promoted dictionary, attach the 14 dense manual features, record
board-size metadata, and label with an STM-perspective WDL target.

Reuses python/v2 helpers (mine_patterns, pattern_contract, dense_features); no
new dependencies. See docs/plans/20260724-v2-extract-examples.md.
"""
import argparse
import collections
import json
import os
import sys

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
# allow `python3 python/v2/extract_examples.py` (script dir, not repo root, on sys.path)
if _REPO_ROOT not in sys.path:
    sys.path.insert(0, _REPO_ROOT)

from python.v2.mine_patterns import (
    _board_from_corpus_line,
    decode_v1_record,
    window_signature,
)
from python.v2.pattern_contract import CellKind, PatternContract
from python.v2.dense_features import extract_dense_features


def load_dictionary(path):
    """Load the promoted dictionary; return the `pattern_to_id` dict."""
    with open(path) as f:
        return json.load(f)["pattern_to_id"]


def board_to_grid(board):
    """Adapt a pattern_contract.Board to the dict-grid extract_dense_features wants.

    Each cell is None for EMPTY, else {'kind': <CellKind name>, 'owner': int}.
    Board size comes from board.rows/board.cols (never hardcoded).
    """
    grid = []
    for r in range(board.rows):
        row = []
        for c in range(board.cols):
            cell = board.get_cell(r, c)
            if cell is None or cell.kind == CellKind.EMPTY:
                row.append(None)
            else:
                row.append({"kind": cell.kind.name, "owner": cell.owner})
        grid.append(row)
    return grid


def pattern_counts(board, stm_owner, pattern_to_id):
    """Sparse COUNTED pattern ids for one perspective: {str(id): count}.

    Per-occurrence counts (not one-hot, not board-size normalized). Signatures
    absent from the dictionary are skipped. Keys are stringified ids so the map
    round-trips through JSON cleanly.
    """
    counts = collections.Counter()
    for window in PatternContract.extract_windows(board, stm_owner):
        pid = pattern_to_id.get(window_signature(window))
        if pid is not None:
            counts[str(pid)] += 1
    return dict(counts)


def wdl_from_target(target):
    """STM-perspective WDL reduction of the continuous target."""
    if target > 0:
        return 1.0
    if target < 0:
        return 0.0
    return 0.5


def extract_example(record, pattern_to_id):
    """One training example dict from a raw {features, target} record."""
    board = decode_v1_record(record["features"])
    grid = board_to_grid(board)
    return {
        "stm_pattern_counts": pattern_counts(board, 1, pattern_to_id),
        "nstm_pattern_counts": pattern_counts(board, 2, pattern_to_id),
        "dense": extract_dense_features(
            grid, active_player=1, turn_number=0, rows=board.rows, cols=board.cols
        ),
        "rows": board.rows,
        "cols": board.cols,
        "wdl": wdl_from_target(record["target"]),
    }


def iter_examples(dataset_path, pattern_to_id):
    """Yield one example per corpus record, in file order."""
    with open(dataset_path) as f:
        records = json.load(f)
    for rec in records:
        yield extract_example(rec, pattern_to_id)


def extract_example_corpus(obj, pattern_to_id):
    """One training example from a v2 raw-board corpus line.

    Schema: {"rows","cols","cells":[[{"kind","owner"},...],...],"stm","wdl"}.
    The perspective is the line's own `stm` (1 or 2), NOT a fixed owner 1 — so
    the model sees genuine STM/NSTM splits across both players. `wdl` is the REAL
    STM-perspective outcome (0.0/0.5/1.0, draws included) and is passed straight
    through. Board size comes from rows/cols; nothing is hardcoded to 12x12.
    """
    board = _board_from_corpus_line(obj)
    stm = obj["stm"]
    nstm = 3 - stm  # two-player diagonal-base setup: owners are 1 and 2
    grid = board_to_grid(board)
    return {
        "stm_pattern_counts": pattern_counts(board, stm, pattern_to_id),
        "nstm_pattern_counts": pattern_counts(board, nstm, pattern_to_id),
        "dense": extract_dense_features(
            grid, active_player=stm, turn_number=0, rows=board.rows, cols=board.cols
        ),
        "rows": board.rows,
        "cols": board.cols,
        "wdl": float(obj["wdl"]),
    }


def iter_examples_corpus(corpus_path, pattern_to_id):
    """Yield one example per raw-corpus JSONL line, in file order."""
    with open(corpus_path) as f:
        for line in f:
            line = line.strip()
            if line:
                yield extract_example_corpus(json.loads(line), pattern_to_id)


def main(argv=None):
    parser = argparse.ArgumentParser(description="Extract NNUE v2 training examples.")
    parser.add_argument("--dataset", default=os.path.join(_REPO_ROOT, "dataset.json"))
    parser.add_argument(
        "--corpus", default=None,
        help="v2 raw-board JSONL corpus (real-outcome WDL); overrides --dataset",
    )
    parser.add_argument(
        "--dict",
        default=os.path.join(_REPO_ROOT, "python", "v2", "nnue_v2_dictionary.json"),
    )
    parser.add_argument(
        "--out",
        default=os.path.join(_REPO_ROOT, "python", "v2", "nnue_v2_examples.jsonl"),
    )
    args = parser.parse_args(argv)

    pattern_to_id = load_dictionary(args.dict)

    source = args.corpus if args.corpus else args.dataset
    examples = (iter_examples_corpus(args.corpus, pattern_to_id) if args.corpus
                else iter_examples(args.dataset, pattern_to_id))

    count = 0
    last = None
    with open(args.out, "w") as f:
        for example in examples:
            f.write(json.dumps(example, sort_keys=True))
            f.write("\n")
            last = example
            count += 1

    print("dataset:  %s" % source)
    print("dict:     %s" % args.dict)
    print("examples: %d" % count)
    print("wrote:    %s" % args.out)
    if last is not None:
        print("sample:   %s" % json.dumps(last, sort_keys=True))


if __name__ == "__main__":
    main()
