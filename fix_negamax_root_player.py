import re

with open("src/main/java/com/engine/nnue_trainer/search/SearchEngine.java", "r") as f:
    text = f.read()

# Add rootPlayer to negamax
text = text.replace(
    'public float negamax(\n      Board board,\n      Accumulator accumulator,\n      int depth,\n      float alpha,\n      float beta,\n      int player,\n      long startTime,\n      long timeLimitMs) {',
    'public float negamax(\n      Board board,\n      Accumulator accumulator,\n      int depth,\n      float alpha,\n      float beta,\n      int player,\n      int rootPlayer,\n      long startTime,\n      long timeLimitMs) {'
)

text = text.replace(
    'public float negamax(Board board, int depth, float alpha, float beta, int player) {\n    return negamax(board, depth, alpha, beta, player, 0, Long.MAX_VALUE);\n  }',
    'public float negamax(Board board, int depth, float alpha, float beta, int player) {\n    return negamax(board, depth, alpha, beta, player, player, 0, Long.MAX_VALUE);\n  }'
)

text = text.replace(
    'public float negamax(\n      Board board,\n      int depth,\n      float alpha,\n      float beta,\n      int player,\n      long startTime,\n      long timeLimitMs) {\n    return negamax(\n        board, null, depth, alpha, beta, player, startTime, timeLimitMs);\n  }',
    'public float negamax(\n      Board board,\n      int depth,\n      float alpha,\n      float beta,\n      int player,\n      int rootPlayer,\n      long startTime,\n      long timeLimitMs) {\n    return negamax(\n        board, null, depth, alpha, beta, player, rootPlayer, startTime, timeLimitMs);\n  }'
)


text = text.replace(
    '-negamax(child, childAcc, depth - 1, -beta, -alpha, getOpponent(player), startTime, timeLimitMs)',
    '-negamax(child, childAcc, depth - 1, -beta, -alpha, getOpponent(player), rootPlayer, startTime, timeLimitMs)'
)
text = text.replace(
    '-negamax(child, childAcc, depth - 1, -alpha - 1, -alpha, getOpponent(player), startTime, timeLimitMs)',
    '-negamax(child, childAcc, depth - 1, -alpha - 1, -alpha, getOpponent(player), rootPlayer, startTime, timeLimitMs)'
)

# And in root calls:
text = text.replace(
    '-negamax(\n                child,\n                childAcc,\n                depth - 1,\n                Float.NEGATIVE_INFINITY,\n                Float.POSITIVE_INFINITY,\n                3 - player,\n                startTime,\n                Long.MAX_VALUE)',
    '-negamax(\n                child,\n                childAcc,\n                depth - 1,\n                Float.NEGATIVE_INFINITY,\n                Float.POSITIVE_INFINITY,\n                3 - player,\n                player,\n                startTime,\n                Long.MAX_VALUE)'
)
text = text.replace(
    '-negamax(\n                child,\n                childAcc,\n                depth - 1,\n                -bestValue - 1.0f,\n                -bestValue,\n                3 - player,\n                startTime,\n                Long.MAX_VALUE)',
    '-negamax(\n                child,\n                childAcc,\n                depth - 1,\n                -bestValue - 1.0f,\n                -bestValue,\n                3 - player,\n                player,\n                startTime,\n                Long.MAX_VALUE)'
)
text = text.replace(
    '-negamax(\n                  child,\n                  childAcc,\n                  depth - 1,\n                  -Float.POSITIVE_INFINITY,\n                  -value,\n                  3 - player,\n                  startTime,\n                  Long.MAX_VALUE)',
    '-negamax(\n                  child,\n                  childAcc,\n                  depth - 1,\n                  -Float.POSITIVE_INFINITY,\n                  -value,\n                  3 - player,\n                  player,\n                  startTime,\n                  Long.MAX_VALUE)'
)

text = text.replace(
    '-negamax(\n                    child,\n                    childAcc,\n                    depth - 1,\n                    Float.NEGATIVE_INFINITY,\n                    Float.POSITIVE_INFINITY,\n                    3 - player,\n                    startTime,\n                    timeLimitMs)',
    '-negamax(\n                    child,\n                    childAcc,\n                    depth - 1,\n                    Float.NEGATIVE_INFINITY,\n                    Float.POSITIVE_INFINITY,\n                    3 - player,\n                    player,\n                    startTime,\n                    timeLimitMs)'
)
text = text.replace(
    '-negamax(\n                    child,\n                    childAcc,\n                    depth - 1,\n                    -alpha - 1.0f,\n                    -alpha,\n                    3 - player,\n                    startTime,\n                    timeLimitMs)',
    '-negamax(\n                    child,\n                    childAcc,\n                    depth - 1,\n                    -alpha - 1.0f,\n                    -alpha,\n                    3 - player,\n                    player,\n                    startTime,\n                    timeLimitMs)'
)
text = text.replace(
    '-negamax(\n                      child,\n                      childAcc,\n                      depth - 1,\n                      Float.NEGATIVE_INFINITY,\n                      -value,\n                      3 - player,\n                      startTime,\n                      timeLimitMs)',
    '-negamax(\n                      child,\n                      childAcc,\n                      depth - 1,\n                      Float.NEGATIVE_INFINITY,\n                      -value,\n                      3 - player,\n                      player,\n                      startTime,\n                      timeLimitMs)'
)

# Replace player with rootPlayer for computeDiff
text = text.replace('Accumulator.computeDiff(board, child, childAcc, player, nnueModel);', 'Accumulator.computeDiff(board, child, childAcc, rootPlayer, nnueModel);')

# quiescenceSearch
text = text.replace(
    'public float quiescenceSearch(\n      Board board,\n      Accumulator accumulator,\n      float alpha,\n      float beta,\n      int player,\n      long startTime,\n      long timeLimitMs) {',
    'public float quiescenceSearch(\n      Board board,\n      Accumulator accumulator,\n      float alpha,\n      float beta,\n      int player,\n      int rootPlayer,\n      long startTime,\n      long timeLimitMs) {'
)
text = text.replace(
    'return quiescenceSearch(\n        board, accumulator, alpha, beta, player, startTime, timeLimitMs, 0);',
    'return quiescenceSearch(\n        board, accumulator, alpha, beta, player, rootPlayer, startTime, timeLimitMs, 0);'
)

text = text.replace(
    'public float quiescenceSearch(\n      Board board,\n      Accumulator accumulator,\n      float alpha,\n      float beta,\n      int player,\n      long startTime,\n      long timeLimitMs,\n      int qsDepth) {',
    'public float quiescenceSearch(\n      Board board,\n      Accumulator accumulator,\n      float alpha,\n      float beta,\n      int player,\n      int rootPlayer,\n      long startTime,\n      long timeLimitMs,\n      int qsDepth) {'
)
text = text.replace(
    '-quiescenceSearch(\n          child,\n          childAcc,\n          -beta,\n          -alpha,\n          getOpponent(player),\n          startTime,\n          timeLimitMs,\n          qsDepth + 1);',
    '-quiescenceSearch(\n          child,\n          childAcc,\n          -beta,\n          -alpha,\n          getOpponent(player),\n          rootPlayer,\n          startTime,\n          timeLimitMs,\n          qsDepth + 1);'
)

text = text.replace(
    'return quiescenceSearch(\n            board, accumulator, alpha, beta, player, startTime, timeLimitMs);',
    'return quiescenceSearch(\n            board, accumulator, alpha, beta, player, rootPlayer, startTime, timeLimitMs);'
)


# Evaluate calls
text = text.replace(
    'return evaluate(board, accumulator, player);',
    'float eval = evaluate(board, accumulator, rootPlayer);\n      return player == rootPlayer ? eval : -eval;'
)
text = text.replace(
    'float standPat = evaluate(board, accumulator, player);',
    'float eval = evaluate(board, accumulator, rootPlayer);\n    float standPat = player == rootPlayer ? eval : -eval;'
)


# update test signatures
with open("src/test/java/com/engine/nnue_trainer/search/SearchEngineUpgradeTest.java", "r") as f:
    text2 = f.read()

text2 = text2.replace(
    '1,\n            startTime1,',
    '1,\n            1,\n            startTime1,'
)
text2 = text2.replace(
    '1,\n            startTime2,',
    '1,\n            1,\n            startTime2,'
)

with open("src/main/java/com/engine/nnue_trainer/search/SearchEngine.java", "w") as f:
    f.write(text)

with open("src/test/java/com/engine/nnue_trainer/search/SearchEngineUpgradeTest.java", "w") as f:
    f.write(text2)
