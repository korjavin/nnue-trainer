import unittest
from python.v2.extract_v2_dataset import V2DatasetExtractor
from python.v2.pattern_contract import Board, Cell, CellKind

class TestV2DatasetExtractor(unittest.TestCase):
    def setUp(self):
        self.extractor = V2DatasetExtractor()
        # Mock dictionary
        self.extractor.dictionary = {
            "d:7,s:8,8,8,8,8,8,8,8,8,8,8,8,4,0,0,8,8,0,0,0,8,8,0,0,0": 1,
            "d:7,s:8,8,8,8,8,8,8,8,8,8,8,8,5,0,0,8,8,0,0,0,8,8,0,0,0": 2
        }

    def test_extract_record_format(self):
        records = [{
            "board": {
                "rows": 3,
                "cols": 3,
                "cells": [
                    [{"kind": "NORMAL", "owner": 1}, None, None],
                    [None, None, None],
                    [None, None, None]
                ]
            },
            "active_player": 1,
            "winner": 1,
            "turn_number": 10
        }]

        dataset = self.extractor.process_records(records, subsample_rate=1.0)
        self.assertEqual(len(dataset), 1)
        record = dataset[0]

        self.assertIn("sparse_stm", record)
        self.assertIn("sparse_nstm", record)
        self.assertIn("dense14", record)
        self.assertIn("wdl_target", record)
        self.assertIn("board_size", record)

        self.assertEqual(record["board_size"], [3, 3])
        self.assertEqual(record["wdl_target"], 1.0)
        self.assertEqual(len(record["dense14"]), 14)

        # sparse_stm should contain pattern 1
        found_pat_1 = any(pat_id == 1 for pat_id, count in record["sparse_stm"])
        self.assertTrue(found_pat_1)

    def test_target_perspective_flipping(self):
        records = [
            {
                "board": {"rows": 3, "cols": 3, "cells": [[None, None, None], [None, None, None], [None, None, None]]},
                "active_player": 1,
                "winner": 1
            },
            {
                "board": {"rows": 3, "cols": 3, "cells": [[None, None, None], [None, None, None], [None, None, None]]},
                "active_player": 2,
                "winner": 1
            },
            {
                "board": {"rows": 3, "cols": 3, "cells": [[None, None, None], [None, None, None], [None, None, None]]},
                "active_player": 1,
                "winner": 0
            }
        ]

        dataset = self.extractor.process_records(records, subsample_rate=1.0)
        self.assertEqual(dataset[0]["wdl_target"], 1.0) # p1 active, p1 wins
        self.assertEqual(dataset[1]["wdl_target"], 0.0) # p2 active, p1 wins
        self.assertEqual(dataset[2]["wdl_target"], 0.5) # draw

    def test_subsampling_is_deterministic(self):
        ext1 = V2DatasetExtractor(seed=42)
        ext2 = V2DatasetExtractor(seed=42)

        records = [{"board": {"rows": 3, "cols": 3, "cells": [[None, None, None], [None, None, None], [None, None, None]]}, "active_player": 1, "winner": 0} for _ in range(100)]

        dataset1 = ext1.process_records(records, subsample_rate=0.5)
        dataset2 = ext2.process_records(records, subsample_rate=0.5)

        self.assertEqual(len(dataset1), len(dataset2))

if __name__ == '__main__':
    unittest.main()
