import numpy as np

def update_accumulator(accumulator_instance, old_board, new_board, modified_positions, accum_stm, accum_nstm, active_player):
    """
    Incrementally updates accum_stm and accum_nstm.
    accumulator_instance: an instance of NNUEv2Accumulator
    old_board: 2D list of cells before the move
    new_board: 2D list of cells after the move
    modified_positions: list of (row, col) that changed
    accum_stm: numpy array of shape (K,) (modified in place)
    accum_nstm: numpy array of shape (K,) (modified in place)
    active_player: STM
    """
    rows = len(old_board)
    cols = len(old_board[0]) if rows > 0 else 0
    nstm_player = 3 - active_player

    affected_centers = set()
    for r, c in modified_positions:
        for dr in range(-2, 3):
            for dc in range(-2, 3):
                cr, cc = r + dr, c + dc
                if 0 <= cr < rows and 0 <= cc < cols:
                    affected_centers.add((cr, cc))

    for cr, cc in affected_centers:
        # Check old board to subtract
        old_cell = old_board[cr][cc]
        if old_cell is not None:
            owner, kind = old_cell
            if kind != 0 and kind != 2:  # Not EMPTY and Not BASE
                pattern_stm_old = accumulator_instance.extract_pattern(old_board, cr, cc, active_player)
                if pattern_stm_old in accumulator_instance.pattern_dict:
                    pid = accumulator_instance.pattern_dict[pattern_stm_old]
                    accum_stm -= accumulator_instance.hidden_weights[pid]

                pattern_nstm_old = accumulator_instance.extract_pattern(old_board, cr, cc, nstm_player)
                if pattern_nstm_old in accumulator_instance.pattern_dict:
                    pid = accumulator_instance.pattern_dict[pattern_nstm_old]
                    accum_nstm -= accumulator_instance.hidden_weights[pid]

        # Check new board to add
        new_cell = new_board[cr][cc]
        if new_cell is not None:
            owner, kind = new_cell
            if kind != 0 and kind != 2:  # Not EMPTY and Not BASE
                pattern_stm_new = accumulator_instance.extract_pattern(new_board, cr, cc, active_player)
                if pattern_stm_new in accumulator_instance.pattern_dict:
                    pid = accumulator_instance.pattern_dict[pattern_stm_new]
                    accum_stm += accumulator_instance.hidden_weights[pid]

                pattern_nstm_new = accumulator_instance.extract_pattern(new_board, cr, cc, nstm_player)
                if pattern_nstm_new in accumulator_instance.pattern_dict:
                    pid = accumulator_instance.pattern_dict[pattern_nstm_new]
                    accum_nstm += accumulator_instance.hidden_weights[pid]

    return accum_stm, accum_nstm
