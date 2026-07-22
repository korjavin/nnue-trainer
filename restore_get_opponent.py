with open("src/main/java/com/engine/nnue_trainer/search/SearchEngine.java.bak", "r") as f:
    orig = f.read()

getop_start = orig.find("  protected int getOpponent(int player) {")
getop_end = orig.find("  public boolean isTerminal(Board board) {")

getop = orig[getop_start:getop_end]

with open("src/main/java/com/engine/nnue_trainer/search/SearchEngine.java", "r") as f:
    text = f.read()

utils_start = text.find("  public boolean isTerminal(Board board) {")

new_text = text[:utils_start] + getop + text[utils_start:]

with open("src/main/java/com/engine/nnue_trainer/search/SearchEngine.java", "w") as f:
    f.write(new_text)
