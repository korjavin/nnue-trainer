"""Checks the fixture generator's board reconstruction and the COMMITTED fixture.

The committed-fixture check is the one that earns its keep: it recomputes every
expected score from nnue_v3_weights.json, so a refit that forgets to regenerate
src/test/resources/v3/eval_parity_fixture.json fails here instead of surfacing
as a mystery parity failure on the Java side.
"""
import json
import os
import unittest

import numpy as np

from python.v2.pattern_contract import Board, Cell, CellKind, PatternContract
from python.v3.gen_v3_eval_fixture import (
    BOARD,
    _OUT_PATH,
    _WEIGHTS,
    active_of,
    board_from_active,
    load_weights,
)


class ReconstructionTest(unittest.TestCase):
    def test_round_trips_every_state(self):
        b = Board(BOARD, BOARD)
        b.set_cell(0, 0, Cell(1, CellKind.BASE))
        b.set_cell(0, 1, Cell(2, CellKind.BASE))
        b.set_cell(1, 0, Cell(1, CellKind.NORMAL))
        b.set_cell(1, 1, Cell(2, CellKind.NORMAL))
        b.set_cell(2, 0, Cell(1, CellKind.FORTIFIED))
        b.set_cell(2, 1, Cell(2, CellKind.FORTIFIED))
        b.set_cell(3, 0, Cell(0, CellKind.NEUTRAL))
        active = active_of(b, 1)
        self.assertEqual(sorted(set(f % 8 for f in active)), list(range(8)))
        self.assertEqual(active_of(board_from_active(active), 1), active)

    def test_rejects_wrong_length(self):
        with self.assertRaises(SystemExit):
            board_from_active([0, 8, 16])


class CommittedFixtureTest(unittest.TestCase):
    def test_expected_scores_match_committed_weights(self):
        if not os.path.exists(_OUT_PATH):
            self.skipTest("no committed fixture")
        with open(_OUT_PATH) as f:
            doc = json.load(f)
        ids, w, bias = load_weights(_WEIGHTS)
        weights = np.zeros(1152)
        weights[ids] = w

        self.assertGreaterEqual(len(doc["fixtures"]), 8)
        for fx in doc["fixtures"]:
            b = Board(BOARD, BOARD)
            for cell in fx["cells"]:
                b.set_cell(cell["r"], cell["c"], Cell(cell["owner"], CellKind[cell["kind"]]))
            pred = bias + sum(weights[f] for f in active_of(b, fx["stm"]))
            self.assertAlmostEqual(
                fx["expected"], pred, places=6, msg="%s: regenerate the fixture" % fx["name"]
            )
        # Both perspectives, or the fixture cannot catch a self/opponent mix-up.
        self.assertEqual({1, 2}, {fx["stm"] for fx in doc["fixtures"]})
        self.assertEqual(PatternContract.NORMAL_SELF, 4)  # contract the ids are built on


if __name__ == "__main__":
    unittest.main()
