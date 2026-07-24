import json
import random
import argparse
from typing import List, Dict, Any, Tuple
from collections import defaultdict
from python.v2.pattern_contract import Board, Cell, CellKind, PatternContract
from python.v2.dense_features import extract_dense_features

class V2DatasetExtractor:
    def __init__(self, dictionary_path: str = None, seed: int = 42):
        self.dictionary: Dict[str, int] = {}
        if dictionary_path:
            with open(dictionary_path, 'r') as f:
                self.dictionary = json.load(f)
        self.rng = random.Random(seed)

    def _parse_board(self, board_data: dict) -> Board:
        rows = board_data.get('rows', 12)
        cols = board_data.get('cols', 12)
        board = Board(rows, cols)

        cells_data = board_data.get('cells', [])
        for r, row in enumerate(cells_data):
            for c, cell_dict in enumerate(row):
                if cell_dict and 'kind' in cell_dict and 'owner' in cell_dict:
                    kind_str = cell_dict['kind']
                    owner = cell_dict['owner']
                    kind = getattr(CellKind, kind_str, CellKind.EMPTY)
                    board.set_cell(r, c, Cell(owner, kind))
        return board

    def _get_pattern_string(self, window: PatternContract.Window) -> str:
        # Construct pattern string in the format expected by the dictionary
        return f"d:{window.distance_bucket},s:{','.join(map(str, window.symbols))}"

    def _extract_sparse(self, board: Board, stm: int) -> List[Tuple[int, int]]:
        windows = PatternContract.extract_windows(board, stm)
        counts = defaultdict(int)
        for w in windows:
            pat_str = self._get_pattern_string(w)
            if self.dictionary:
                if pat_str in self.dictionary:
                    counts[self.dictionary[pat_str]] += 1
            else:
                pass

        return [[pat_id, count] for pat_id, count in counts.items()]

    def process_records(self, records: List[Dict[str, Any]], subsample_rate: float = 1.0) -> List[Dict[str, Any]]:
        dataset = []
        for record in records:
            if self.rng.random() > subsample_rate:
                continue

            board_data = record.get('board')
            board = self._parse_board(board_data)
            stm = record.get('active_player', record.get('stm', 1))
            turn_number = record.get('turn_number', 0)

            nstm = 3 - stm

            sparse_stm = self._extract_sparse(board, stm)
            sparse_nstm = self._extract_sparse(board, nstm)

            # To extract dense features, we need board as an array of dicts with 'kind' and 'owner'
            raw_board = [[None for _ in range(board.cols)] for _ in range(board.rows)]
            for r in range(board.rows):
                for c in range(board.cols):
                    cell = board.get_cell(r, c)
                    if cell:
                        raw_board[r][c] = {'kind': cell.kind.name, 'owner': cell.owner}

            dense14 = extract_dense_features(raw_board, stm, turn_number, board.rows, board.cols)

            winner = record.get('winner', 0)
            if winner == stm:
                wdl_target = 1.0
            elif winner == 0:
                wdl_target = 0.5
            else:
                wdl_target = 0.0

            dataset.append({
                'sparse_stm': sparse_stm,
                'sparse_nstm': sparse_nstm,
                'dense14': dense14,
                'wdl_target': wdl_target,
                'board_size': [board.rows, board.cols]
            })

        return dataset

    def process_file(self, input_path: str, output_path: str, subsample_rate: float = 1.0):
        with open(input_path, 'r') as f:
            records = json.load(f)

        dataset = self.process_records(records, subsample_rate)

        with open(output_path, 'w') as f:
            json.dump(dataset, f, indent=2)

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--input', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--dict', required=False, default=None)
    parser.add_argument('--subsample', type=float, default=1.0)
    args = parser.parse_args()

    extractor = V2DatasetExtractor(args.dict)
    extractor.process_file(args.input, args.output, args.subsample)
