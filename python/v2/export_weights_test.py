"""Tests for the NNUE v2 weights exporter. Run from repo root:

    python3 -m unittest discover -s python/v2 -p "*_test.py"
"""
import json
import os
import tempfile
import unittest

import torch

from python.v2.export_weights import load_model, main, model_to_weights, weights_metadata
from python.v2.train_v2 import NNUEv2


class WeightsMetadataTest(unittest.TestCase):
    def test_shapes_and_concat_dim(self):
        model = NNUEv2(7, W=8)
        meta = weights_metadata(model)
        self.assertEqual(meta["concat_dim"], 2 * 8 + 14)
        self.assertEqual(meta["layers"]["l1"]["in"], 2 * 8 + 14)
        self.assertEqual(meta["embeddings"]["stm_embed"], [7, 8])


class ExportRoundTripTest(unittest.TestCase):
    def test_export_reload_matches_model(self):
        torch.manual_seed(0)
        num_patterns, W = 11, 8
        model = NNUEv2(num_patterns, W=W)

        with tempfile.TemporaryDirectory() as d:
            model_path = os.path.join(d, "m.pt")
            dict_path = os.path.join(d, "dict.json")
            weights_path = os.path.join(d, "w.json")
            meta_path = os.path.join(d, "meta.json")
            torch.save(model.state_dict(), model_path)
            with open(dict_path, "w") as f:
                json.dump({"pattern_to_id": {},
                           "metadata": {"num_patterns": num_patterns}}, f)

            main(["--model", model_path, "--dict", dict_path, "--width", str(W),
                  "--out-weights", weights_path, "--out-meta", meta_path])

            with open(weights_path) as f:
                w = json.load(f)

        # Exported floats must reproduce the trained tensors exactly (Java-loadable).
        self.assertEqual(torch.tensor(w["stm_embed"]).shape, (num_patterns, W))
        self.assertTrue(torch.allclose(torch.tensor(w["stm_embed"]), model.stm_embed.weight))
        self.assertTrue(torch.allclose(torch.tensor(w["nstm_embed"]), model.nstm_embed.weight))
        self.assertTrue(torch.allclose(torch.tensor(w["l1"]["weight"]), model.l1.weight))
        self.assertTrue(torch.allclose(torch.tensor(w["l1"]["bias"]), model.l1.bias))
        self.assertTrue(torch.allclose(torch.tensor(w["l3"]["weight"]), model.l3.weight))

    def test_load_model_roundtrips_state(self):
        num_patterns, W = 5, 4
        model = NNUEv2(num_patterns, W=W)
        with tempfile.TemporaryDirectory() as d:
            model_path = os.path.join(d, "m.pt")
            dict_path = os.path.join(d, "dict.json")
            torch.save(model.state_dict(), model_path)
            with open(dict_path, "w") as f:
                json.dump({"metadata": {"num_patterns": num_patterns}}, f)
            reloaded = load_model(model_path, dict_path, W)
        w = model_to_weights(reloaded)
        self.assertTrue(torch.allclose(torch.tensor(w["l2"]["weight"]), model.l2.weight))


if __name__ == "__main__":
    unittest.main()
