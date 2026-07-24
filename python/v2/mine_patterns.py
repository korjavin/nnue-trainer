"""Offline miner: extract a promoted 5x5 pattern dictionary from real positions.

Reuses python/v2/pattern_contract.py for window extraction and perspective
normalization. See docs/plans/20260724-v2-mine-5x5-pattern-dictionary.md.
"""
import argparse
import collections
import json
import os
import sys

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
# allow `python3 python/v2/mine_patterns.py` (script dir, not repo root, on sys.path)
if _REPO_ROOT not in sys.path:
    sys.path.insert(0, _REPO_ROOT)

from python.v2.pattern_contract import Board, Cell, CellKind, PatternContract

# v1 864-dim encoding: each cell has a 6-way one-hot at offset cellIndex*6.
# Index -> (owner, CellKind). owner 1=self, 2=opponent, 0=none.
# BASE is not stored by the v1 encoder (known limitation): no base -> distance_bucket==7.
_V1_ONEHOT = {
    0: (0, CellKind.EMPTY),
    1: (1, CellKind.NORMAL),
    2: (2, CellKind.NORMAL),
    3: (1, CellKind.FORTIFIED),
    4: (2, CellKind.FORTIFIED),
    5: (0, CellKind.NEUTRAL),
}


def window_signature(window):
    """Canonical deterministic signature string for a Window.

    Perspective is already normalized by the contract; no rotation/mirror dedup.
    """
    return ",".join(str(s) for s in window.symbols) + "|" + str(window.distance_bucket)


def decode_v1_record(features):
    """Decode a v1 864-dim one-hot feature vector into a pattern_contract.Board.

    Board size is derived, never hardcoded: side = round(sqrt(len/6)).
    """
    side = int(round((len(features) / 6) ** 0.5))
    if side * side * 6 != len(features):
        raise ValueError(
            "features length %d is not side*side*6 for any integer side" % len(features)
        )
    board = Board(side, side)
    for cell_index in range(side * side):
        base = cell_index * 6
        onehot = features[base:base + 6]
        # active class = the one-hot index that is set (default EMPTY)
        active = 0
        for k in range(6):
            if onehot[k]:
                active = k
                break
        owner, kind = _V1_ONEHOT[active]
        if kind != CellKind.EMPTY:
            r, c = divmod(cell_index, side)
            board.set_cell(r, c, Cell(owner, kind))
    return board


def iter_boards(dataset_path):
    """Yield a decoded Board per record, in file order."""
    with open(dataset_path) as f:
        records = json.load(f)
    for rec in records:
        yield decode_v1_record(rec["features"])


def count_signatures(boards, stm_owner=1):
    """Count window signatures across boards.

    Returns (Counter, total_window_count).
    """
    counter = collections.Counter()
    total = 0
    for board in boards:
        for window in PatternContract.extract_windows(board, stm_owner):
            counter[window_signature(window)] += 1
            total += 1
    return counter, total


def build_dictionary(counter, min_count):
    """Promote signatures with count >= min_count into an id map.

    Returns (pattern_to_id, retained_count, total_promoted_occurrences).
    Ids 0..N-1 assigned by stable sort key (-count, signature).
    """
    promoted = [(sig, cnt) for sig, cnt in counter.items() if cnt >= min_count]
    promoted.sort(key=lambda sc: (-sc[1], sc[0]))
    pattern_to_id = {sig: i for i, (sig, _) in enumerate(promoted)}
    total_promoted_occurrences = sum(cnt for _, cnt in promoted)
    return pattern_to_id, len(promoted), total_promoted_occurrences


def export_dictionary(pattern_to_id, min_count, out_path):
    """Write the dictionary JSON deterministically (sort_keys, indent=2)."""
    doc = {
        "pattern_to_id": pattern_to_id,
        "metadata": {
            "num_patterns": len(pattern_to_id),
            "min_count": min_count,
            "version": 2,
        },
    }
    with open(out_path, "w") as f:
        json.dump(doc, f, sort_keys=True, indent=2)
        f.write("\n")


def main(argv=None):
    parser = argparse.ArgumentParser(description="Mine 5x5 pattern dictionary.")
    parser.add_argument("--dataset", default=os.path.join(_REPO_ROOT, "dataset.json"))
    parser.add_argument("--min-count", type=int, default=5)
    parser.add_argument(
        "--out", default=os.path.join(_REPO_ROOT, "python", "v2", "nnue_v2_dictionary.json")
    )
    args = parser.parse_args(argv)

    counter, total = count_signatures(iter_boards(args.dataset))
    pattern_to_id, retained, promoted_occ = build_dictionary(counter, args.min_count)
    coverage = (promoted_occ / total * 100.0) if total else 0.0

    export_dictionary(pattern_to_id, args.min_count, args.out)

    print("dataset:            %s" % args.dataset)
    print("total windows:      %d" % total)
    print("distinct signatures:%d" % len(counter))
    print("min_count:          %d" % args.min_count)
    print("num_patterns:       %d" % retained)
    print("retained coverage:  %.2f%% (%d/%d occurrences)" % (coverage, promoted_occ, total))
    if not (2000 <= retained <= 10000):
        print("NOTE: num_patterns %d is outside the 2k-10k target range." % retained)
    print("wrote:              %s" % args.out)


if __name__ == "__main__":
    main()
