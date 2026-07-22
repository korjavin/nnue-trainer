import re

with open("src/main/java/com/engine/nnue_trainer/search/SearchEngine.java", "r") as f:
    code = f.read()

# 1. Update evaluate signature and implementation
code = re.sub(
    r'protected float evaluate\(Board board, int player, boolean maximizingPlayer\) \{\s*return evaluate\(board, null, player, maximizingPlayer\);\s*\}',
    r'protected float evaluate(Board board, int player, int rootPlayer) {\n    return evaluate(board, null, player, rootPlayer);\n  }',
    code
)

eval_sig_old = r'protected float evaluate\(\s*Board board, Accumulator accumulator, int player, boolean maximizingPlayer\)'
eval_sig_new = r'protected float evaluate(\n      Board board, Accumulator accumulator, int player, int rootPlayer)'
code = re.sub(eval_sig_old, eval_sig_new, code)

# Fix the internal variables of evaluate
code = code.replace(
    'int originalPlayer = maximizingPlayer ? player : getOpponent(player);',
    'int originalPlayer = rootPlayer;'
)

# Fix the return of evaluate to be relative to the side to move. Wait, no, we want it relative to 'player'.
# Actually, in standard Negamax, evaluate returns the score relative to the current node's player (side to move).
# If we just change the evaluate to return the score from the perspective of 'player':
