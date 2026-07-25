import unittest
from python.v2.dense_features import extract_dense_features

class TestDenseFeatures(unittest.TestCase):
    def test_bounds_and_symmetry(self):
        board = [[{'kind': 'EMPTY', 'owner': 0} for _ in range(12)] for _ in range(12)]
        board[0][0] = {'kind': 'BASE', 'owner': 1}
        board[11][11] = {'kind': 'BASE', 'owner': 2}
        board[0][1] = {'kind': 'NORMAL', 'owner': 1}
        board[11][10] = {'kind': 'NORMAL', 'owner': 2}

        f1 = extract_dense_features(board, 1, 10)
        f2 = extract_dense_features(board, 2, 10)

        self.assertEqual(len(f1), 14)
        self.assertEqual(len(f2), 14)

        for i in range(14):
            self.assertTrue(0.0 <= f1[i] <= 1.0, f"Feature {i} out of bounds: {f1[i]}")
            self.assertTrue(0.0 <= f2[i] <= 1.0, f"Feature {i} out of bounds: {f2[i]}")

        # Symmetry check
        self.assertAlmostEqual(f1[0], f2[1], places=6)
        self.assertAlmostEqual(f1[1], f2[0], places=6)
        self.assertAlmostEqual(f1[2], f2[3], places=6)
        self.assertAlmostEqual(f1[3], f2[2], places=6)
        self.assertAlmostEqual(f1[4], f2[4], places=6)
        self.assertAlmostEqual(f1[5], f2[5], places=6)
        self.assertAlmostEqual(f1[6], f2[7], places=6)
        self.assertAlmostEqual(f1[7], f2[6], places=6)
        self.assertAlmostEqual(f1[8], f2[9], places=6)
        self.assertAlmostEqual(f1[9], f2[8], places=6)
        self.assertAlmostEqual(f1[10], f2[11], places=6)
        self.assertAlmostEqual(f1[11], f2[10], places=6)
        self.assertAlmostEqual(f1[12], f2[12], places=6)
        self.assertAlmostEqual(f1[13], f2[13], places=6)

if __name__ == '__main__':
    unittest.main()
