import re

with open("src/main/java/com/engine/nnue_trainer/search/SearchEngine.java", "r") as f:
    lines = f.readlines()

out = []
in_alpha_beta = False
in_qs = False
in_eval = False

for line in lines:
    out.append(line)

# Let's use a simpler approach - we'll just write the entire SearchEngine.java file to avoid regex complexities.
# We'll use awk or python to extract the non-search parts.
