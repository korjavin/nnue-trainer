"""Tests for the pattern miner core. Run from repo root:

    python3 -m unittest discover -s python/v2 -p "*_test.py"
"""
import collections
import os
import tempfile
import unittest

from python.v2.mine_patterns import (
    build_dictionary,
    count_signatures,
    count_signatures_both,
    decode_v1_record,
    export_dictionary,
    window_signature,
)
from python.v2.pattern_contract import Board, Cell, CellKind, PatternContract


class WindowSignatureTest(unittest.TestCase):
    def test_stable_and_deterministic(self):
        w = PatternContract.Window(0, 0, [0, 1, 2, 8, 4], 3)
        self.assertEqual(window_signature(w), "0,1,2,8,4|3")
        self.assertEqual(window_signature(w), window_signature(w))


class DecodeV1RecordTest(unittest.TestCase):
    def _blank(self, side=12):
        return [0] * (side * side * 6)

    def _set(self, features, side, r, c, onehot_idx):
        features[(r * side + c) * 6 + onehot_idx] = 1

    def test_round_trip(self):
        side = 12
        f = self._blank(side)
        self._set(f, side, 1, 1, 1)  # NORMAL_self
        self._set(f, side, 2, 3, 2)  # NORMAL_opp
        self._set(f, side, 4, 5, 5)  # NEUTRAL
        board = decode_v1_record(f)
        self.assertEqual(board.rows, side)
        self.assertEqual(board.cols, side)

        c11 = board.get_cell(1, 1)
        self.assertEqual((c11.owner, c11.kind), (1, CellKind.NORMAL))
        c23 = board.get_cell(2, 3)
        self.assertEqual((c23.owner, c23.kind), (2, CellKind.NORMAL))
        c45 = board.get_cell(4, 5)
        self.assertEqual(c45.kind, CellKind.NEUTRAL)
        # untouched cell stays empty
        self.assertEqual(board.get_cell(0, 0).kind, CellKind.EMPTY)

    def test_non_square_raises(self):
        with self.assertRaises(ValueError):
            decode_v1_record([0] * 5)  # 5/6 is not a perfect square


class CountSignaturesTest(unittest.TestCase):
    def test_single_cell_emits_neighborhood(self):
        # One filled cell at the center of a 5x5 board: every one of the 25
        # window centers has that cell in its 5x5 neighborhood -> 25 windows.
        board = Board(5, 5)
        board.set_cell(2, 2, Cell(1, CellKind.NORMAL))
        counter, total = count_signatures([board], stm_owner=1)
        self.assertEqual(total, 25)
        self.assertEqual(sum(counter.values()), 25)


class CountSignaturesBothTest(unittest.TestCase):
    def test_dictionary_covers_nstm_windows(self):
        # An asymmetric board (self NORMAL vs opponent FORTIFIED) has NSTM
        # (owner-2) windows whose SELF/OPP symbols are swapped vs the STM view.
        # Mining owner-1 only leaves NSTM windows unmatched; mining both covers
        # them. This is what the two-accumulator model (accumulator.py) needs.
        board = Board(5, 5)
        board.set_cell(1, 1, Cell(1, CellKind.NORMAL))
        board.set_cell(3, 3, Cell(2, CellKind.FORTIFIED))

        counter1, _ = count_signatures([board], stm_owner=1)
        dict1, _, _ = build_dictionary(counter1, min_count=1)
        nstm_sigs = [
            window_signature(w)
            for w in PatternContract.extract_windows(board, 2)
        ]
        self.assertTrue(nstm_sigs)
        self.assertFalse(any(s in dict1 for s in nstm_sigs))  # owner-1-only misses

        counter_both, _ = count_signatures_both([board])
        dict_both, _, _ = build_dictionary(counter_both, min_count=1)
        self.assertTrue(all(s in dict_both for s in nstm_sigs))  # both covers NSTM
        stm_sigs = [
            window_signature(w)
            for w in PatternContract.extract_windows(board, 1)
        ]
        self.assertTrue(all(s in dict_both for s in stm_sigs))  # and still STM


class BuildDictionaryTest(unittest.TestCase):
    def test_promotes_and_assigns_contiguous_ids(self):
        counter = collections.Counter({"a": 5, "b": 3, "c": 2, "d": 1})
        pattern_to_id, retained, occ = build_dictionary(counter, min_count=3)
        self.assertEqual(retained, 2)
        self.assertEqual(occ, 8)
        # sorted by (-count, signature): a(5) then b(3)
        self.assertEqual(pattern_to_id, {"a": 0, "b": 1})

    def test_tiebreak_by_signature(self):
        counter = collections.Counter({"z": 2, "a": 2})
        pattern_to_id, _, _ = build_dictionary(counter, min_count=2)
        self.assertEqual(pattern_to_id, {"a": 0, "z": 1})

    def test_deterministic(self):
        counter = collections.Counter({"a": 5, "b": 5, "c": 5})
        first, _, _ = build_dictionary(counter, min_count=1)
        second, _, _ = build_dictionary(counter, min_count=1)
        self.assertEqual(first, second)


class EndToEndDeterminismTest(unittest.TestCase):
    def _boards(self):
        b1 = Board(5, 5)
        b1.set_cell(2, 2, Cell(1, CellKind.NORMAL))
        b2 = Board(6, 6)
        b2.set_cell(3, 3, Cell(2, CellKind.FORTIFIED))
        return [b1, b2]

    def test_same_boards_yield_identical_map(self):
        c1, _ = count_signatures(self._boards())
        d1, _, _ = build_dictionary(c1, min_count=1)
        c2, _ = count_signatures(self._boards())
        d2, _, _ = build_dictionary(c2, min_count=1)
        self.assertEqual(d1, d2)

    def test_serialized_output_is_byte_identical(self):
        # The hard requirement is byte-identical JSON, not just equal dicts.
        with tempfile.TemporaryDirectory() as d:
            p1 = os.path.join(d, "a.json")
            p2 = os.path.join(d, "b.json")
            for out in (p1, p2):
                c, _ = count_signatures(self._boards())
                pattern_to_id, _, _ = build_dictionary(c, min_count=1)
                export_dictionary(pattern_to_id, min_count=1, out_path=out)
            with open(p1, "rb") as f1, open(p2, "rb") as f2:
                self.assertEqual(f1.read(), f2.read())


if __name__ == "__main__":
    unittest.main()
