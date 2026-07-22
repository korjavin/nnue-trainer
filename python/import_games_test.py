import unittest
import os
import json
import sqlite3
from unittest.mock import patch
import sys
import tempfile

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))
import import_games

class TestImportGames(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.db_path = os.path.join(self.temp_dir.name, "games_test.db")
        self.output_path = os.path.join(self.temp_dir.name, "dataset_test.json")

        # Patch paths via import_games or just os.environ if that's how it's accessed
        # Wait, import_games hardcodes paths?
        # Ah, earlier we mocked it by modifying import_games.py to use os.environ.get.
        self.env_patcher = patch.dict(os.environ, {'DB_PATH': self.db_path, 'OUTPUT_PATH': self.output_path})
        self.env_patcher.start()


        conn = sqlite3.connect(self.db_path)
        cursor = conn.cursor()
        cursor.execute("""
            CREATE TABLE games (
                id INTEGER PRIMARY KEY,
                result INTEGER,
                pgn_content TEXT,
                rows INTEGER,
                cols INTEGER,
                player1_name TEXT,
                player2_name TEXT
            )
        """)

        # turn 0: player 1, score 100 (which is 0.1 eval)
        # turn 1: player 2, score 200 (which is 0.2 eval)
        pgn = json.dumps([
            {"player": 1, "moves": [{"type": "place", "row": 0, "col": 1, "score": 100.0}]},
            {"player": 2, "moves": [{"type": "place", "row": 10, "col": 10, "score": 200.0}]}
        ])

        cursor.execute("""
            INSERT INTO games (id, result, pgn_content, rows, cols, player1_name, player2_name)
            VALUES (1, 1, ?, 12, 12, 'GoBot1', 'GoBot2')
        """, (pgn,))

        # Game 2: No score
        pgn_no_score = json.dumps([
            {"player": 1, "moves": [{"type": "place", "row": 0, "col": 1}]}
        ])
        cursor.execute("""
            INSERT INTO games (id, result, pgn_content, rows, cols, player1_name, player2_name)
            VALUES (2, 1, ?, 12, 12, 'GoBot1', 'GoBot2')
        """, (pgn_no_score,))

        conn.commit()
        conn.close()

    def tearDown(self):
        self.temp_dir.cleanup()
        self.env_patcher.stop()

    def test_import_games_outcome(self):
        with patch('sys.argv', ['import_games.py', '--label-mode', 'outcome']):
            import_games.main()

        with open(self.output_path, "r") as f:
            data = json.load(f)

        self.assertEqual(len(data), 3) # 2 from game 1, 1 from game 2
        self.assertEqual(data[0]['eval'], 1.0)
        self.assertEqual(data[1]['eval'], -1.0)

    def test_import_games_discounted(self):
        with patch('sys.argv', ['import_games.py', '--label-mode', 'discounted', '--gamma-val', '0.9']):
            import_games.main()

        with open(self.output_path, "r") as f:
            data = json.load(f)

        # distance = total_turns - turn_index
        # Game 1: 2 turns
        # turn 0: dist 2 => target = 1.0 * 0.9^2 = 0.81
        self.assertAlmostEqual(data[0]['eval'], 0.81)
        # turn 1: dist 1 => target = -1.0 * 0.9^1 = -0.9
        self.assertAlmostEqual(data[1]['eval'], -0.9)

    def test_import_games_td_leaf(self):
        # We need to filter out game 2, let's delete it so td_leaf doesn't fail, or we expect failure
        conn = sqlite3.connect(self.db_path)
        cursor = conn.cursor()
        cursor.execute("DELETE FROM games WHERE id = 2")
        conn.commit()
        conn.close()

        with patch('sys.argv', ['import_games.py', '--label-mode', 'td_leaf', '--lambda-val', '0.5']):
            import_games.main()

        with open(self.output_path, "r") as f:
            data = json.load(f)

        # turn 0: outcome = 1.0, eval = 0.1, distance = 2
        # current_lambda = 0.5^2 = 0.25
        # target = 0.75 * 0.1 + 0.25 * 1.0 = 0.325
        self.assertAlmostEqual(data[0]['eval'], 0.325)

        # turn 1: outcome = -1.0, eval = 0.2, distance = 1
        # current_lambda = 0.5^1 = 0.5
        # target = 0.5 * 0.2 + 0.5 * (-1.0) = -0.4
        self.assertAlmostEqual(data[1]['eval'], -0.4)

    def test_import_games_td_leaf_missing_score(self):
        with patch('sys.argv', ['import_games.py', '--label-mode', 'td_leaf']):
            with self.assertRaises(ValueError):
                import_games.main()

    def test_import_games_validations(self):
        with patch('sys.argv', ['import_games.py', '--lambda-val', '1.5']):
            with self.assertRaises(ValueError):
                import_games.main()

        with patch('sys.argv', ['import_games.py', '--gamma-val', '-0.5']):
            with self.assertRaises(ValueError):
                import_games.main()

if __name__ == '__main__':
    unittest.main()
