import unittest
import torch
import torch.nn as nn
import torch.optim as optim
import os
import json
from nnue_v2_model import NNUEv2

class TestNNUEv2Model(unittest.TestCase):
    def setUp(self):
        self.vocab_size = 1000
        self.batch_size = 4
        self.model = NNUEv2(vocab_size=self.vocab_size)

    def test_forward_pass_shapes(self):
        # Create synthetic data
        stm_indices = torch.randint(0, self.vocab_size, (20,))
        stm_offsets = torch.tensor([0, 5, 10, 15])

        nstm_indices = torch.randint(0, self.vocab_size, (16,))
        nstm_offsets = torch.tensor([0, 4, 8, 12])

        dense_features = torch.randn(self.batch_size, 14)

        out = self.model(stm_indices, stm_offsets, nstm_indices, nstm_offsets, dense_features)
        self.assertEqual(out.shape, (self.batch_size, 1))

    def test_gradient_flow_and_convergence(self):
        # Create synthetic data for convergence
        stm_indices = torch.randint(0, self.vocab_size, (20,))
        stm_offsets = torch.tensor([0, 5, 10, 15])

        nstm_indices = torch.randint(0, self.vocab_size, (16,))
        nstm_offsets = torch.tensor([0, 4, 8, 12])

        dense_features = torch.randn(self.batch_size, 14)

        # Targets: WDL loss (win=1.0, draw=0.5, loss=0.0) -> Let's use BCEWithLogitsLoss
        targets = torch.tensor([[1.0], [0.0], [0.5], [1.0]])

        criterion = nn.BCEWithLogitsLoss()
        optimizer = optim.SGD(self.model.parameters(), lr=0.1)

        initial_loss = None
        final_loss = None

        for i in range(100):
            optimizer.zero_grad()
            out = self.model(stm_indices, stm_offsets, nstm_indices, nstm_offsets, dense_features)
            loss = criterion(out, targets)
            loss.backward()
            optimizer.step()

            if i == 0:
                initial_loss = loss.item()
            if i == 99:
                final_loss = loss.item()

        self.assertIsNotNone(initial_loss)
        self.assertIsNotNone(final_loss)
        self.assertLess(final_loss, initial_loss)

    def test_export_weights(self):
        export_path = "nnue_v2_weights_test.json"
        self.model.export_weights(export_path)

        self.assertTrue(os.path.exists(export_path))

        with open(export_path, 'r') as f:
            data = json.load(f)

        self.assertEqual(data["vocab_size"], self.vocab_size)
        self.assertEqual(data["embedding_dim"], 1024)
        self.assertEqual(data["dense_dim"], 14)
        self.assertEqual(data["hidden_dim"], 32)

        self.assertIn("embedding.weight", data["weights"])
        self.assertIn("fc1.weight", data["weights"])
        self.assertIn("fc1.bias", data["weights"])
        self.assertIn("fc2.weight", data["weights"])
        self.assertIn("fc2.bias", data["weights"])

        os.remove(export_path)

if __name__ == '__main__':
    unittest.main()
