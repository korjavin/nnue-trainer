import re

with open("src/test/java/com/engine/nnue_trainer/search/SearchEngineTest.java", "r") as f:
    text = f.read()

text = text.replace(
    'return super.evaluate(board, null, player, maximizingPlayer);',
    'return super.evaluate(board, null, player);'
)

text = text.replace(
    'return super.evaluate(board, accumulator, player, true);',
    'return super.evaluate(board, accumulator, player);'
)

text = text.replace(
    'return super.evaluate(board, accumulator, player, false);',
    'return super.evaluate(board, accumulator, player);'
)

text = re.sub(
    r'engine\.alphaBeta\(.*?\);',
    'engine.negamax(board, 3, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, 1);',
    text
)

text = text.replace(
    'engine.evaluate(board, 1, true)',
    'engine.evaluate(board, 1)'
)

text = text.replace(
    'engine.evaluate(board, 2, false)',
    'engine.evaluate(board, 2)'
)

text = text.replace(
    'float eval1 = engine.alphaBeta(board, 1, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, 1, true);',
    'float eval1 = engine.negamax(board, 1, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, 1);'
)

text = text.replace(
    'float eval2 = engine.alphaBeta(board, 1, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, 2, false);',
    'float eval2 = engine.negamax(board, 1, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, 2);'
)

with open("src/test/java/com/engine/nnue_trainer/search/SearchEngineTest.java", "w") as f:
    f.write(text)

with open("src/test/java/com/engine/nnue_trainer/search/SearchEngineUpgradeTest.java", "r") as f:
    text2 = f.read()

text2 = text2.replace(
    'engine.negamax(\n            board,\n            null,\n            3,\n            Float.NEGATIVE_INFINITY,\n            Float.POSITIVE_INFINITY,\n            1,\n            0,\n            Long.MAX_VALUE);',
    'engine.negamax(\n            board,\n            null,\n            3,\n            Float.NEGATIVE_INFINITY,\n            Float.POSITIVE_INFINITY,\n            1,\n            0,\n            Long.MAX_VALUE);'
)

with open("src/test/java/com/engine/nnue_trainer/search/SearchEngineUpgradeTest.java", "w") as f:
    f.write(text2)
