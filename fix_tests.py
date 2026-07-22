import re

with open("src/test/java/com/engine/nnue_trainer/search/SearchEngineTest.java", "r") as f:
    text = f.read()

# Replace test alphaBeta methods in MockSearchEngine
text = text.replace(
    'protected float evaluate(Board board, int player, boolean maximizingPlayer) {',
    'protected float evaluate(Board board, int player) {'
)

text = text.replace(
    'protected float evaluate(Board board, Accumulator accumulator, int player, boolean maximizingPlayer) {',
    'protected float evaluate(Board board, Accumulator accumulator, int player) {'
)

text = text.replace(
    'return super.evaluate(board, null, player, true);',
    'return super.evaluate(board, null, player);'
)

text = text.replace(
    'float eval1 = engine.evaluate(board, 1, true);',
    'float eval1 = engine.evaluate(board, 1);'
)

text = text.replace(
    'float eval2 = engine.evaluate(board, 2, false);',
    'float eval2 = engine.evaluate(board, 2);'
)

with open("src/test/java/com/engine/nnue_trainer/search/SearchEngineTest.java", "w") as f:
    f.write(text)

with open("src/test/java/com/engine/nnue_trainer/search/SearchEngineUpgradeTest.java", "r") as f:
    text2 = f.read()

text2 = text2.replace(
    'engine.alphaBeta(',
    'engine.negamax('
)

text2 = text2.replace(
    'false,\n            0,\n            Long.MAX_VALUE',
    '0,\n            Long.MAX_VALUE'
)


with open("src/test/java/com/engine/nnue_trainer/search/SearchEngineUpgradeTest.java", "w") as f:
    f.write(text2)
