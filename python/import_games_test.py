import unittest
import os
import json
import sqlite3
import sys

# Add root directory to sys.path
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))
import import_games

class TestImportGames(unittest.TestCase):
    def setUp(self):
        self.db_path = "/tmp/games_test.db"
        self.output_path = "/tmp/dataset_test.json"
        os.environ["DB_PATH"] = self.db_path
        os.environ["OUTPUT_PATH"] = self.output_path

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

        # Player 1 won (result = 1)
        cursor.execute("""
            INSERT INTO games (id, result, pgn_content, rows, cols, player1_name, player2_name)
            VALUES (1, 1, ?, 12, 12, 'GoBot1', 'GoBot2')
        """, (pgn,))
        conn.commit()
        conn.close()

    def tearDown(self):
        if os.path.exists(self.db_path):
            os.remove(self.db_path)
        if os.path.exists(self.output_path):
            os.remove(self.output_path)

    def test_import_games_outcome(self):
        sys.argv = ['import_games.py', '--label-mode', 'outcome']
        import_games.main()

        with open(self.output_path, "r") as f:
            data = json.load(f)

        self.assertEqual(len(data), 2)
        self.assertEqual(data[0]['eval'], 1.0)
        self.assertEqual(data[1]['eval'], -1.0)  # perspective of player 2, result is 1 so player 2 lost

    def test_import_games_discounted(self):
        sys.argv = ['import_games.py', '--label-mode', 'discounted', '--gamma-val', '0.9']
        import_games.main()

        with open(self.output_path, "r") as f:
            data = json.load(f)

        self.assertEqual(len(data), 2)
        # turn 0 (distance 1): target = 1.0 * 0.9^1 = 0.9
        self.assertAlmostEqual(data[0]['eval'], 0.9)
        # turn 1 (distance 0): target = -1.0 * 0.9^0 = -1.0
        self.assertAlmostEqual(data[1]['eval'], -1.0)

    def test_import_games_td_leaf(self):
        sys.argv = ['import_games.py', '--label-mode', 'td_leaf', '--lambda-val', '0.5']
        import_games.main()

        with open(self.output_path, "r") as f:
            data = json.load(f)

        self.assertEqual(len(data), 2)
        # turn 0: outcome = 1.0, search_eval = 0.1
        # distance = 1, current_lambda = 0.5^1 = 0.5
        # target = (1 - 0.5)*0.1 + 0.5*1.0 = 0.05 + 0.5 = 0.55
        self.assertAlmostEqual(data[0]['eval'], 0.55)

        # turn 1: outcome = -1.0 (perspective of player 2), search_eval = 0.2
        # distance = 0, current_lambda = 0.5^0 = 1.0
        # target = (1 - 1.0)*0.2 + 1.0*(-1.0) = -1.0
        self.assertAlmostEqual(data[1]['eval'], -1.0)

if __name__ == '__main__':
    unittest.main()
