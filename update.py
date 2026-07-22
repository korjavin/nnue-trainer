with open("src/main/java/com/engine/nnue_trainer/search/SearchEngine.java", "r") as f:
    text = f.read()
print(text.find("public float alphaBeta("))
print(text.find("public static Board applyAction"))
