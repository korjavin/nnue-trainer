import re

with open("src/main/java/com/engine/nnue_trainer/search/SearchEngine.java.bak", "r") as f:
    orig = f.read()

utils_start = orig.find("  public boolean isTerminal(Board board) {")
utils_end = orig.find("  protected float evaluate(Board board, int player, boolean maximizingPlayer) {")

utils = orig[utils_start:utils_end]

with open("src/main/java/com/engine/nnue_trainer/search/SearchEngine.java", "r") as f:
    text = f.read()

eval_start = text.find("  protected float evaluate(Board board, int player) {")

new_text = text[:eval_start] + utils + text[eval_start:]

with open("src/main/java/com/engine/nnue_trainer/search/SearchEngine.java", "w") as f:
    f.write(new_text)
