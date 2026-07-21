import numpy as np

class NNUEv2Accumulator:
    def __init__(self, pattern_dict, hidden_weights, K, dense_size=14):
        """
        pattern_dict: mapping from 25-tuple (or similar) to pattern_id.
                      Wait, we need to define the signature of the 5x5 window.
                      Let's say it's a tuple of 25 ints.
        hidden_weights: numpy array of shape (num_patterns, K)
        K: size of the accumulator
        dense_size: size of the dense features (e.g. 14)
        """
        self.pattern_dict = pattern_dict
        self.hidden_weights = hidden_weights
        self.K = K
        self.dense_size = dense_size

    def extract_pattern(self, board_cells, row, col, perspective_player):
        """
        Extracts a 5x5 window around (row, col) from the board.
        board_cells: 2D array of (owner, kind) or just cell state.
        We need to know the state mapping.
        Let's use the same mapping as getFeatureState:
        0: out of bounds/empty/base
        1: STM normal
        2: NSTM normal
        3: STM fortified
        4: NSTM fortified
        5: neutral
        """
        rows = len(board_cells)
        cols = len(board_cells[0])
        pattern = []

        for r in range(row - 2, row + 3):
            for c in range(col - 2, col + 3):
                if 0 <= r < rows and 0 <= c < cols:
                    cell = board_cells[r][c]
                    if cell is None:
                        pattern.append(0)
                        continue

                    owner, kind = cell
                    # kind: 0=EMPTY, 1=NORMAL, 2=BASE, 3=FORTIFIED, 4=NEUTRAL
                    # Mapping:
                    # 0=EMPTY, 2=BASE -> 0
                    if kind == 0 or kind == 2:
                        pattern.append(0)
                    elif kind == 1: # NORMAL
                        if owner == perspective_player:
                            pattern.append(1)
                        else:
                            pattern.append(2)
                    elif kind == 3: # FORTIFIED
                        if owner == perspective_player:
                            pattern.append(3)
                        else:
                            pattern.append(4)
                    elif kind == 4: # NEUTRAL
                        pattern.append(5)
                    else:
                        pattern.append(0)
                else:
                    pattern.append(0) # Out of bounds
        return tuple(pattern)

    def compute_full(self, board_cells, active_player, dense_features=None):
        """
        Computes the full accumulator for the given board.
        board_cells: list of lists representing the board grid.
                     Each cell is a tuple (owner, kind), or None.
        active_player: the side to move (STM).
        dense_features: list/array of 14 floats. If None, zeros are used.
        """
        rows = len(board_cells)
        cols = len(board_cells[0])

        accum_stm = np.zeros(self.K, dtype=np.float32)
        accum_nstm = np.zeros(self.K, dtype=np.float32)

        nstm_player = 3 - active_player

        for r in range(rows):
            for c in range(cols):
                # Extract for STM
                pattern_stm = self.extract_pattern(board_cells, r, c, active_player)
                if pattern_stm in self.pattern_dict:
                    pattern_id = self.pattern_dict[pattern_stm]
                    accum_stm += self.hidden_weights[pattern_id]

                # Extract for NSTM
                pattern_nstm = self.extract_pattern(board_cells, r, c, nstm_player)
                if pattern_nstm in self.pattern_dict:
                    pattern_id = self.pattern_dict[pattern_nstm]
                    accum_nstm += self.hidden_weights[pattern_id]

        if dense_features is None:
            dense_features = np.zeros(self.dense_size, dtype=np.float32)
        else:
            dense_features = np.array(dense_features, dtype=np.float32)

        return np.concatenate([accum_stm, accum_nstm, dense_features])
