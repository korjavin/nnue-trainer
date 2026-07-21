import numpy as np

class AccumulatorV2:
    """
    Python reference accumulator for NNUE v2.
    Implements a 5x5 pattern signature contract and full recompute.
    """
    def __init__(self, stm_weights, nstm_weights, dense_biases, k=1024):
        """
        stm_weights: dict mapping pattern_id (int) to numpy array of size K
        nstm_weights: dict mapping pattern_id (int) to numpy array of size K
        dense_biases: numpy array of size K for initial biases for STM/NSTM
        k: Size of the accumulator buffer (default 1024)
        """
        self.stm_weights = stm_weights
        self.nstm_weights = nstm_weights
        self.dense_biases = dense_biases
        self.k = k

    def extract_pattern(self, board_state, center_r, center_c, stm_perspective):
        """
        Given a board_state (2D array), extract 5x5 pattern around (center_r, center_c).
        Returns a pattern ID.
        """
        rows, cols = board_state.shape
        pattern = []
        for r in range(center_r - 2, center_r + 3):
            for c in range(center_c - 2, center_c + 3):
                if 0 <= r < rows and 0 <= c < cols:
                    val = board_state[r, c]
                    if not stm_perspective:
                        if val == 1: val = 2
                        elif val == 2: val = 1
                        elif val == 3: val = 4
                        elif val == 4: val = 3
                    pattern.append(val)
                else:
                    pattern.append(-1) # Out of bounds

        # Serialize to int ID safely
        # To avoid python overflow using standard integer instead of numpy scalar
        pattern_id = 0
        for p in pattern:
            pattern_id = pattern_id * 7 + int(p + 1)
        return pattern_id

    def forward(self, board_state, dense_14):
        """
        board_state: 2D numpy array representing the board (variable size).
        dense_14: numpy array of size 14 representing dense features.
        """
        rows, cols = board_state.shape

        accum_stm = np.copy(self.dense_biases)
        accum_nstm = np.copy(self.dense_biases)

        for r in range(rows):
            for c in range(cols):
                stm_pattern_id = self.extract_pattern(board_state, r, c, True)
                if stm_pattern_id in self.stm_weights:
                    accum_stm += self.stm_weights[stm_pattern_id]

                nstm_pattern_id = self.extract_pattern(board_state, r, c, False)
                if nstm_pattern_id in self.nstm_weights:
                    accum_nstm += self.nstm_weights[nstm_pattern_id]

        return np.concatenate((accum_stm, accum_nstm, dense_14))
