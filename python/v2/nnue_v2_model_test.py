import unittest
import torch
import torch.nn as nn
import torch.optim as optim
import os
import json
from python.v2.nnue_v2_model import NNUEv2
from python.v2.train_v2 import DummyNNUEDataset

class TestNNUEv2Model(unittest.TestCase):
    def setUp(self):
        self.num_patterns = 100
        self.k = 1024
        self.dense_size = 14
        self.hidden_size = 32
        self.model = NNUEv2(num_patterns=self.num_patterns, k=self.k, dense_size=self.dense_size, hidden_size=self.hidden_size)

    def test_forward_pass_shapes(self):
        batch_size = 8
        seq_len = 15

        sparse_stm = torch.randint(0, self.num_patterns, (batch_size, seq_len))
        sparse_nstm = torch.randint(0, self.num_patterns, (batch_size, seq_len))
        dense14 = torch.rand((batch_size, self.dense_size))

        out = self.model(sparse_stm, sparse_nstm, dense14)

        # Verify shape
        self.assertEqual(out.shape, (batch_size, 1))

        # Verify output is bounded between 0 and 1 due to Sigmoid
        self.assertTrue(torch.all(out >= 0.0) and torch.all(out <= 1.0))

    def test_gradient_flow_and_convergence(self):
        # Create a small dataset and check if loss decreases
        batch_size = 16
        seq_len = 10

        sparse_stm = torch.randint(0, self.num_patterns, (batch_size, seq_len))
        sparse_nstm = torch.randint(0, self.num_patterns, (batch_size, seq_len))
        dense14 = torch.rand((batch_size, self.dense_size))

        # Fixed targets to ensure it can learn
        targets = torch.ones((batch_size, 1))

        optimizer = optim.Adam(self.model.parameters(), lr=0.01)
        criterion = nn.BCELoss()

        # Initial forward pass
        initial_out = self.model(sparse_stm, sparse_nstm, dense14)
        initial_loss = criterion(initial_out, targets)

        # Training steps
        for _ in range(20):
            optimizer.zero_grad()
            out = self.model(sparse_stm, sparse_nstm, dense14)
            loss = criterion(out, targets)
            loss.backward()
            optimizer.step()

        # Final forward pass
        final_out = self.model(sparse_stm, sparse_nstm, dense14)
        final_loss = criterion(final_out, targets)

        self.assertTrue(final_loss.item() < initial_loss.item())

    def test_export(self):
        filepath = "test_nnue_v2_export.json"

        # Export
        self.model.export_to_json(filepath)
        self.assertTrue(os.path.exists(filepath))

        # Verify JSON
        with open(filepath, "r") as f:
            data = json.load(f)

        self.assertIn("config", data)
        self.assertIn("weights", data)

        self.assertEqual(data["config"]["num_patterns"], self.num_patterns)
        self.assertEqual(data["config"]["k"], self.k)
        self.assertEqual(data["config"]["dense_size"], self.dense_size)

        # Cleanup
        os.remove(filepath)

if __name__ == "__main__":
    unittest.main()
