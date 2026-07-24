import json
import os
import tempfile
import unittest

import torch

from python.v2.train_v2 import NNUEv2, collate, main, read_num_patterns, train


def _ex(stm, nstm, dense=None, wdl=1.0):
    return {
        "stm_pattern_counts": stm,
        "nstm_pattern_counts": nstm,
        "dense": dense if dense is not None else [0.0] * 14,
        "wdl": wdl,
    }


class TestModel(unittest.TestCase):
    def test_forward_shape_and_gradients(self):
        model = NNUEv2(num_patterns=100, W=8)
        batch = collate([
            _ex({"1": 2, "5": 1}, {"3": 1}),
            _ex({"7": 1}, {"9": 3, "2": 1}),
            _ex({"4": 1}, {"6": 1}),
        ])
        out = model(batch["stm_ids"], batch["stm_off"], batch["stm_w"],
                    batch["nstm_ids"], batch["nstm_off"], batch["nstm_w"],
                    batch["dense"])
        self.assertEqual(out.shape, (3,))
        loss = torch.nn.functional.mse_loss(out, batch["wdl"])
        loss.backward()
        self.assertIsNotNone(model.stm_embed.weight.grad)
        self.assertIsNotNone(model.nstm_embed.weight.grad)
        self.assertGreater(model.stm_embed.weight.grad.abs().sum().item(), 0.0)
        self.assertGreater(model.nstm_embed.weight.grad.abs().sum().item(), 0.0)

    def test_empty_stm_bag_is_finite_and_zero_accumulator(self):
        model = NNUEv2(num_patterns=100, W=8)
        # dict-miss: empty STM counts must yield a zero STM accumulator, no crash.
        batch = collate([_ex({}, {"3": 1}), _ex({"4": 1}, {})])
        acc_stm = model.stm_embed(batch["stm_ids"], batch["stm_off"],
                                  per_sample_weights=batch["stm_w"])
        self.assertTrue(torch.all(acc_stm[0] == 0.0))  # first example empty STM
        out = model(batch["stm_ids"], batch["stm_off"], batch["stm_w"],
                    batch["nstm_ids"], batch["nstm_off"], batch["nstm_w"],
                    batch["dense"])
        loss = torch.nn.functional.mse_loss(out, batch["wdl"])
        self.assertTrue(torch.isfinite(loss).item())


class TestTraining(unittest.TestCase):
    def _synthetic(self, n=20):
        ex = []
        for i in range(n):
            ex.append(_ex({str(i % 7): 1 + i % 3}, {str((i + 1) % 5): 1},
                          dense=[float(i % 4)] * 14, wdl=float(i % 2)))
        return ex

    def test_same_seed_is_deterministic(self):
        data = self._synthetic()
        a = train(data, num_patterns=50, W=4, epochs=3, batch_size=4, seed=123)
        b = train(data, num_patterns=50, W=4, epochs=3, batch_size=4, seed=123)
        self.assertEqual(a, b)

    def test_read_num_patterns_from_real_dictionary(self):
        dict_path = os.path.join(os.path.dirname(__file__), "nnue_v2_dictionary.json")
        self.assertEqual(read_num_patterns(dict_path), 5571)


class TestMain(unittest.TestCase):
    def test_main_writes_model_and_metadata(self):
        here = os.path.dirname(__file__)
        dict_path = os.path.join(here, "nnue_v2_dictionary.json")
        num_patterns = read_num_patterns(dict_path)
        with tempfile.TemporaryDirectory() as d:
            # Tiny pre-made examples file so main() doesn't regenerate the corpus.
            ex_path = os.path.join(d, "ex.jsonl")
            with open(ex_path, "w") as f:
                for i in range(8):
                    f.write(json.dumps(_ex({str(i % 7): 1}, {str((i + 1) % 5): 1},
                                           dense=[0.0] * 14, wdl=float(i % 2))) + "\n")
            out_model = os.path.join(d, "model.pt")
            out_meta = os.path.join(d, "meta.json")
            main(["--dict", dict_path, "--examples", ex_path,
                  "--out-model", out_model, "--out-meta", out_meta,
                  "--epochs", "1", "--batch-size", "4", "--width", "4"])
            self.assertTrue(os.path.exists(out_model))
            self.assertTrue(os.path.exists(out_meta))
            with open(out_meta) as f:
                meta = json.load(f)
            self.assertEqual(meta["W"], 4)
            self.assertEqual(meta["num_patterns"], num_patterns)
            self.assertEqual(meta["dense_size"], 14)
            self.assertIn("layers", meta)
            self.assertEqual(meta["layers"]["l1"], [16, 2 * 4 + 14])


if __name__ == "__main__":
    unittest.main()
