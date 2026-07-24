"""Tests for the NNUE v2 example extractor. Run from repo root:

    python3 -m unittest discover -s python/v2 -p "*_test.py"
"""
import unittest

from python.v2.extract_examples import (
    extract_example,
    pattern_counts,
    wdl_from_target,
)
from python.v2.mine_patterns import decode_v1_record, window_signature
from python.v2.pattern_contract import Board, Cell, CellKind, PatternContract


def _dict_for(board, owners=(1, 2)):
    """Build a {signature: id} map from a board's windows over given perspectives."""
    sigs = []
    for owner in owners:
        for w in PatternContract.extract_windows(board, owner):
            sigs.append(window_signature(w))
    return {sig: i for i, sig in enumerate(dict.fromkeys(sigs))}


def _record(side, cells, target):
    """v1 864-style record: features one-hot per cell, plus a target."""
    features = [0] * (side * side * 6)
    for r, c, onehot_idx in cells:
        features[(r * side + c) * 6 + onehot_idx] = 1
    return {"features": features, "target": target}


class PatternCountsTest(unittest.TestCase):
    def test_counted_and_dict_miss_skipped(self):
        # Two identical fully-interior cells whose 5x5 neighborhoods do not
        # overlap produce the window centered on each cell identically (no OOB
        # edge effects) -> per-occurrence count of 2. A dict holding only that
        # one signature proves both counting (value 2) and skipping the rest.
        board = Board(12, 12)
        board.set_cell(5, 3, Cell(1, CellKind.NORMAL))
        board.set_cell(5, 8, Cell(1, CellKind.NORMAL))

        ref = Board(12, 12)
        ref.set_cell(5, 3, Cell(1, CellKind.NORMAL))
        centered = next(
            w for w in PatternContract.extract_windows(ref, 1)
            if (w.center_row, w.center_col) == (5, 3)
        )
        sig = window_signature(centered)

        counts = pattern_counts(board, 1, {sig: 0})
        self.assertEqual(counts, {"0": 2})  # counted, all other sigs skipped

    def test_string_keys(self):
        board = Board(5, 5)
        board.set_cell(2, 2, Cell(1, CellKind.NORMAL))
        counts = pattern_counts(board, 1, _dict_for(board, owners=(1,)))
        self.assertTrue(all(isinstance(k, str) for k in counts))


class WdlFromTargetTest(unittest.TestCase):
    def test_reduction(self):
        self.assertEqual(wdl_from_target(0.7), 1.0)
        self.assertEqual(wdl_from_target(0.0), 0.5)
        self.assertEqual(wdl_from_target(-0.3), 0.0)


class ExtractExampleTest(unittest.TestCase):
    def _asymmetric_record(self, side=5):
        # self NORMAL top-left, opponent FORTIFIED top-right -> asymmetric.
        return _record(side, [(0, 0, 1), (0, 4, 4)], target=0.9)

    def test_shape_and_size(self):
        rec = self._asymmetric_record(side=5)
        p2id = _dict_for(decode_v1_record(rec["features"]))
        ex = extract_example(rec, p2id)
        self.assertEqual(
            set(ex),
            {"stm_pattern_counts", "nstm_pattern_counts", "dense", "rows", "cols", "wdl"},
        )
        self.assertEqual(len(ex["dense"]), 14)
        self.assertEqual((ex["rows"], ex["cols"]), (5, 5))  # board-size-agnostic
        self.assertEqual(ex["wdl"], 1.0)

    def test_stm_nstm_differ_for_asymmetric_board(self):
        rec = self._asymmetric_record(side=5)
        p2id = _dict_for(decode_v1_record(rec["features"]))
        ex = extract_example(rec, p2id)
        self.assertNotEqual(ex["stm_pattern_counts"], ex["nstm_pattern_counts"])

    def test_deterministic(self):
        rec = self._asymmetric_record(side=5)
        p2id = _dict_for(decode_v1_record(rec["features"]))
        self.assertEqual(extract_example(rec, p2id), extract_example(rec, p2id))


if __name__ == "__main__":
    unittest.main()
