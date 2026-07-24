"""Generate the shared Java<->Python accumulator parity fixture.

Builds a handful of boards with pattern_contract.Board/Cell/CellKind, runs
PatternContract.extract_windows for both perspectives (stm_owner in {1,2}),
signs each window with mine_patterns.window_signature, looks the signature up
in the promoted nnue_v2_dictionary.json (skipping misses), and counts per id.

Writes src/test/resources/v2/accumulator_parity_fixture.json deterministically.
The Java parity test rebuilds these boards and asserts the same (id->count)
maps, proving byte-for-byte contract parity.
"""
import collections
import json
import os
import sys

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
if _REPO_ROOT not in sys.path:
    sys.path.insert(0, _REPO_ROOT)

from python.v2.pattern_contract import Board, Cell, CellKind, PatternContract
from python.v2.mine_patterns import window_signature

_DICT_PATH = os.path.join(_REPO_ROOT, "python", "v2", "nnue_v2_dictionary.json")
_OUT_PATH = os.path.join(
    _REPO_ROOT, "src", "test", "resources", "v2", "accumulator_parity_fixture.json"
)


def _board(rows, cols, cells):
    b = Board(rows, cols)
    for r, c, owner, kind in cells:
        b.set_cell(r, c, Cell(owner, kind))
    return b


# Each entry: (name, rows, cols, [(r, c, owner, CellKind), ...])
# - single_piece: no base -> bucket 7 everywhere; owner-1 NORMAL hits symbol-4
#   patterns for perspective 1 and symbol-5 patterns for perspective 2 (both
#   populous in the dictionary), guaranteeing non-empty maps on BOTH sides.
# - small_with_bases: two BASEs so distance buckets vary across windows.
# - non_square: rectangular, non-12x12, variable size.
# - corners: pieces in corners so windows hit OOB edges.
_BOARDS = [
    ("single_piece", 6, 6, [
        (2, 2, 1, CellKind.NORMAL),
    ]),
    ("small_with_bases", 7, 7, [
        (1, 1, 1, CellKind.BASE),
        (5, 5, 2, CellKind.BASE),
        (2, 2, 1, CellKind.NORMAL),
        (4, 4, 2, CellKind.NORMAL),
        (3, 3, 1, CellKind.FORTIFIED),
    ]),
    ("non_square", 5, 8, [
        (2, 3, 1, CellKind.NORMAL),
        (2, 5, 2, CellKind.NORMAL),
    ]),
    ("corners", 6, 6, [
        (0, 0, 1, CellKind.NORMAL),
        (5, 5, 2, CellKind.NORMAL),
    ]),
]


def _count_ids(board, stm_owner, pattern_to_id):
    counts = collections.Counter()
    for window in PatternContract.extract_windows(board, stm_owner):
        idx = pattern_to_id.get(window_signature(window))
        if idx is not None:
            counts[idx] += 1
    # id keys as strings to survive JSON round-trip and match Jackson parsing.
    return {str(idx): cnt for idx, cnt in sorted(counts.items())}


def main():
    with open(_DICT_PATH) as f:
        pattern_to_id = json.load(f)["pattern_to_id"]

    fixtures = []
    for name, rows, cols, cells in _BOARDS:
        board = _board(rows, cols, cells)
        fixtures.append({
            "name": name,
            "rows": rows,
            "cols": cols,
            "cells": [
                {"r": r, "c": c, "owner": owner, "kind": kind.name}
                for r, c, owner, kind in cells
            ],
            "expected": {
                "1": _count_ids(board, 1, pattern_to_id),
                "2": _count_ids(board, 2, pattern_to_id),
            },
        })

    single = next(f for f in fixtures if f["name"] == "single_piece")
    assert single["expected"]["1"] and single["expected"]["2"], (
        "single_piece must hit real dictionary ids for BOTH perspectives"
    )

    os.makedirs(os.path.dirname(_OUT_PATH), exist_ok=True)
    with open(_OUT_PATH, "w") as f:
        json.dump(fixtures, f, sort_keys=True, indent=2)
        f.write("\n")

    print("wrote %s (%d boards)" % (_OUT_PATH, len(fixtures)))
    for fx in fixtures:
        print("  %-16s p1=%d ids  p2=%d ids"
              % (fx["name"], len(fx["expected"]["1"]), len(fx["expected"]["2"])))


if __name__ == "__main__":
    main()
