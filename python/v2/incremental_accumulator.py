import numpy as np

class NNUEv2IncrementalAccumulator:
    def __init__(self, full_accum, rows, cols):
        self.full_accum = full_accum
        self.rows = rows
        self.cols = cols
        self.cached_stm = [[None for _ in range(cols)] for _ in range(rows)]
        self.cached_nstm = [[None for _ in range(cols)] for _ in range(rows)]
        self.acc_stm = np.copy(self.full_accum.hidden_bias)
        self.acc_nstm = np.copy(self.full_accum.hidden_bias)
        self.active_player = 1 # Arbitrary default

    def initialize(self, board_cells, active_player):
        self.active_player = active_player
        self.acc_stm = np.copy(self.full_accum.hidden_bias)
        self.acc_nstm = np.copy(self.full_accum.hidden_bias)
        self.cached_stm = [[None for _ in range(self.cols)] for _ in range(self.rows)]
        self.cached_nstm = [[None for _ in range(self.cols)] for _ in range(self.rows)]

        nstm_player = 3 - active_player

        for r in range(self.rows):
            for c in range(self.cols):
                cell = board_cells[r][c]
                if cell is None:
                    continue
                owner, kind = cell
                if kind == 0 or kind == 2: # EMPTY or BASE
                    continue

                pattern_stm = self.full_accum.extract_pattern(board_cells, r, c, active_player)
                if pattern_stm in self.full_accum.pattern_dict:
                    pattern_id = self.full_accum.pattern_dict[pattern_stm]
                    self.cached_stm[r][c] = pattern_id
                    self.acc_stm += self.full_accum.hidden_weights[pattern_id]

                pattern_nstm = self.full_accum.extract_pattern(board_cells, r, c, nstm_player)
                if pattern_nstm in self.full_accum.pattern_dict:
                    pattern_id = self.full_accum.pattern_dict[pattern_nstm]
                    self.cached_nstm[r][c] = pattern_id
                    self.acc_nstm += self.full_accum.hidden_weights[pattern_id]

    def _swap_players(self):
        self.active_player = 3 - self.active_player
        # Swap caches and accumulators
        self.cached_stm, self.cached_nstm = self.cached_nstm, self.cached_stm
        self.acc_stm, self.acc_nstm = self.acc_nstm, self.acc_stm

    def update(self, board_cells, active_player, modified_cells, dense_features=None):
        if active_player != self.active_player:
            self._swap_players()

        affected_windows = set()
        for r, c in modified_cells:
            for dr in range(-2, 3):
                for dc in range(-2, 3):
                    wr = r + dr
                    wc = c + dc
                    if 0 <= wr < self.rows and 0 <= wc < self.cols:
                        affected_windows.add((wr, wc))

        nstm_player = 3 - active_player

        for r, c in affected_windows:
            # Subtract old
            old_stm_id = self.cached_stm[r][c]
            if old_stm_id is not None:
                self.acc_stm -= self.full_accum.hidden_weights[old_stm_id]
                self.cached_stm[r][c] = None

            old_nstm_id = self.cached_nstm[r][c]
            if old_nstm_id is not None:
                self.acc_nstm -= self.full_accum.hidden_weights[old_nstm_id]
                self.cached_nstm[r][c] = None

            # Add new
            cell = board_cells[r][c]
            if cell is not None:
                owner, kind = cell
                if kind != 0 and kind != 2:
                    # STM
                    pattern_stm = self.full_accum.extract_pattern(board_cells, r, c, active_player)
                    if pattern_stm in self.full_accum.pattern_dict:
                        pattern_id = self.full_accum.pattern_dict[pattern_stm]
                        self.cached_stm[r][c] = pattern_id
                        self.acc_stm += self.full_accum.hidden_weights[pattern_id]

                    # NSTM
                    pattern_nstm = self.full_accum.extract_pattern(board_cells, r, c, nstm_player)
                    if pattern_nstm in self.full_accum.pattern_dict:
                        pattern_id = self.full_accum.pattern_dict[pattern_nstm]
                        self.cached_nstm[r][c] = pattern_id
                        self.acc_nstm += self.full_accum.hidden_weights[pattern_id]

        if dense_features is None:
            dense_features = np.zeros(self.full_accum.dense_size, dtype=np.float32)
        else:
            dense_features = np.array(dense_features, dtype=np.float32)

        return np.concatenate([self.acc_stm, self.acc_nstm, dense_features])
