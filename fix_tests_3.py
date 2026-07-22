import re

with open("src/test/java/com/engine/nnue_trainer/search/SearchEngineTest.java", "r") as f:
    text = f.read()

text = re.sub(
    r'protected float evaluate\(Board board, int player\) \{\s*if \(evaluationMap\.containsKey\(board\)\) \{\s*return evaluationMap\.get\(board\);\s*\}\s*return super\.evaluate\(board, player, maximizingPlayer\);\s*\}',
    r'protected float evaluate(Board board, int player) {\n      if (evaluationMap.containsKey(board)) {\n        return evaluationMap.get(board);\n      }\n      return super.evaluate(board, player);\n    }',
    text
)

text = re.sub(
    r'protected float evaluate\(\s*Board board, Accumulator accumulator, int player\) \{\s*if \(evaluationMap\.containsKey\(board\)\) \{\s*return evaluationMap\.get\(board\);\s*\}\s*return super\.evaluate\(board, accumulator, player, maximizingPlayer\);\s*\}',
    r'protected float evaluate(\n        Board board, Accumulator accumulator, int player) {\n      if (evaluationMap.containsKey(board)) {\n        return evaluationMap.get(board);\n      }\n      return super.evaluate(board, accumulator, player);\n    }',
    text
)

text = text.replace('engine.evaluate(board, 2, true);', 'engine.evaluate(board, 2);')

with open("src/test/java/com/engine/nnue_trainer/search/SearchEngineTest.java", "w") as f:
    f.write(text)
