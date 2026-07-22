import re

with open("src/main/java/com/engine/nnue_trainer/search/SearchEngine.java", "r") as f:
    text = f.read()

text = text.replace(
    'float eval = -quiescenceSearch(',
    'float childEval = -quiescenceSearch('
)
text = text.replace(
    'maxEval = Math.max(maxEval, eval);',
    'maxEval = Math.max(maxEval, childEval);'
)
text = text.replace(
    'alpha = Math.max(alpha, eval);',
    'alpha = Math.max(alpha, childEval);'
)

# Fix undefined rootPlayer in findBestAction* methods
text = text.replace(
    'Accumulator.computeDiff(board, child, childAcc, rootPlayer, nnueModel);',
    'Accumulator.computeDiff(board, child, childAcc, player, nnueModel);'
)
text = text.replace(
    '-negamax(\n                child,\n                childAcc,\n                depth - 1,\n                Float.NEGATIVE_INFINITY,\n                Float.POSITIVE_INFINITY,\n                3 - player,\n                player,\n                startTime,\n                Long.MAX_VALUE)',
    '-negamax(\n                child,\n                childAcc,\n                depth - 1,\n                Float.NEGATIVE_INFINITY,\n                Float.POSITIVE_INFINITY,\n                3 - player,\n                player,\n                startTime,\n                Long.MAX_VALUE)'
)

with open("src/main/java/com/engine/nnue_trainer/search/SearchEngine.java", "w") as f:
    f.write(text)
