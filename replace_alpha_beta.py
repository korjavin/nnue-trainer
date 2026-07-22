import re

with open("src/main/java/com/engine/nnue_trainer/search/SearchEngine.java", "r") as f:
    content = f.read()

# We need to rewrite alphaBeta and quiescenceSearch, as well as the root calls in findBestAction*
# Doing this via regex or string replace might be tedious, so I'll write a python script that replaces the entire SearchEngine.java with a negamax version.
