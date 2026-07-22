import re

with open("src/test/java/com/engine/nnue_trainer/search/SearchEngineTest.java", "r") as f:
    text = f.read()

text = re.sub(
    r'@Override\s+protected float evaluate\(\s*Board board,\s*com\.engine\.nnue_trainer\.nnue\.Accumulator accumulator,\s*int player,\s*boolean maximizingPlayer\)\s*\{',
    '@Override\n    protected float evaluate(\n        Board board,\n        com.engine.nnue_trainer.nnue.Accumulator accumulator,\n        int player) {',
    text
)

with open("src/test/java/com/engine/nnue_trainer/search/SearchEngineTest.java", "w") as f:
    f.write(text)
