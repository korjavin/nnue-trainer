import re

with open("src/test/java/com/engine/nnue_trainer/search/SearchEngineTest.java", "r") as f:
    text = f.read()

text = text.replace('super.evaluate(board, player, maximizingPlayer)', 'super.evaluate(board, player)')
text = text.replace('super.evaluate(board, accumulator, player, maximizingPlayer)', 'super.evaluate(board, accumulator, player)')
text = text.replace('engine.evaluate(board, 2, true)', 'engine.evaluate(board, 2)')
text = text.replace('engine.evaluate(board, 2, false)', 'engine.evaluate(board, 2)')
text = text.replace('engine.evaluate(board, 1, true)', 'engine.evaluate(board, 1)')
text = text.replace('engine.evaluate(board, 1, false)', 'engine.evaluate(board, 1)')


with open("src/test/java/com/engine/nnue_trainer/search/SearchEngineTest.java", "w") as f:
    f.write(text)

with open("src/test/java/com/engine/nnue_trainer/search/SearchEngineUpgradeTest.java", "r") as f:
    text2 = f.read()

text2 = text2.replace(
    '1,\n            true,\n            startTime1,',
    '1,\n            startTime1,'
)

text2 = text2.replace(
    '1,\n            true,\n            startTime2,',
    '1,\n            startTime2,'
)

with open("src/test/java/com/engine/nnue_trainer/search/SearchEngineUpgradeTest.java", "w") as f:
    f.write(text2)
