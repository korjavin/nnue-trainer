import numpy as np

class NNUEv2Accumulator:
    def __init__(self, pattern_dict, hidden_weights, hidden_bias, K, dense_size=14):
        """
        pattern_dict: mapping from 25-tuple (or similar) to pattern_id.
                      Wait, we need to define the signature of the 5x5 window.
                      Let's say it's a tuple of 25 ints.
        hidden_weights: numpy array of shape (num_patterns, K)
        hidden_bias: numpy array of shape (K,)
        K: size of the accumulator
        dense_size: size of the dense features (e.g. 14)
        """
        self.pattern_dict = pattern_dict
        self.hidden_weights = hidden_weights
        self.hidden_bias = hidden_bias
        self.K = K
        self.dense_size = dense_size

        if self.hidden_weights.shape[1] != self.K:
            raise ValueError(f"hidden_weights K dimension {self.hidden_weights.shape[1]} does not match expected {self.K}")
        if self.hidden_bias.shape[0] != self.K:
            raise ValueError(f"hidden_bias dimension {self.hidden_bias.shape[0]} does not match expected {self.K}")

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
                m_dist = abs(row - r) + abs(col - c)

                if 0 <= r < rows and 0 <= c < cols:
                    cell = board_cells[r][c]
                    if cell is None:
                        pattern.append(12) # Out of bounds / Empty
                        continue

                    owner, kind = cell
                    # kind: 0=EMPTY, 1=NORMAL, 2=BASE, 3=FORTIFIED, 4=NEUTRAL
                    if kind == 0:
                        pattern.append(12)
                    elif kind == 2:
                        pattern.append(13)
                    elif kind == 1: # NORMAL
                        if owner == perspective_player:
                            pattern.append(0 + m_dist)
                        else:
                            pattern.append(6 + m_dist)
                    elif kind == 3: # FORTIFIED
                        if owner == perspective_player:
                            pattern.append(0 + m_dist) # Wait, fortified is same as normal? We should check v2 mapping
                        else:
                            pattern.append(6 + m_dist)
                    elif kind == 4: # NEUTRAL
                        pattern.append(14)
                    else:
                        pattern.append(12)
                else:
                    pattern.append(12) # Out of bounds
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

        accum_stm = np.copy(self.hidden_bias)
        accum_nstm = np.copy(self.hidden_bias)

        nstm_player = 3 - active_player

        for r in range(rows):
            for c in range(cols):
                cell = board_cells[r][c]
                # Only active cells emit windows
                if cell is None:
                    continue
                owner, kind = cell
                if kind == 0 or kind == 2: # EMPTY or BASE
                    continue

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
