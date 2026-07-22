import re

with open("src/main/java/com/engine/nnue_trainer/search/SearchEngine.java", "r") as f:
    text = f.read()

# Replace root calls from alphaBeta to negamax
text = text.replace(
    'alphaBeta(\n                child,\n                childAcc,\n                depth - 1,\n                Float.NEGATIVE_INFINITY,\n                Float.POSITIVE_INFINITY,\n                3 - player,\n                false,\n                startTime,\n                Long.MAX_VALUE)',
    '-negamax(\n                child,\n                childAcc,\n                depth - 1,\n                Float.NEGATIVE_INFINITY,\n                Float.POSITIVE_INFINITY,\n                3 - player,\n                startTime,\n                Long.MAX_VALUE)'
)

text = text.replace(
    'alphaBeta(\n                child,\n                childAcc,\n                depth - 1,\n                Float.NEGATIVE_INFINITY,\n                bestValue + 1.0f, // Null window based on bestValue found so far\n                3 - player,\n                false,\n                startTime,\n                Long.MAX_VALUE)',
    '-negamax(\n                child,\n                childAcc,\n                depth - 1,\n                -bestValue - 1.0f,\n                -bestValue,\n                3 - player,\n                startTime,\n                Long.MAX_VALUE)'
)

text = text.replace(
    'alphaBeta(\n                  child,\n                  childAcc,\n                  depth - 1,\n                  value,\n                  Float.POSITIVE_INFINITY,\n                  3 - player,\n                  false,\n                  startTime,\n                  Long.MAX_VALUE)',
    '-negamax(\n                  child,\n                  childAcc,\n                  depth - 1,\n                  -Float.POSITIVE_INFINITY,\n                  -value,\n                  3 - player,\n                  startTime,\n                  Long.MAX_VALUE)'
)


# findBestActionWithTimeLimitUsingModel replacements
text = text.replace(
    'alphaBeta(\n                    child,\n                    childAcc,\n                    depth - 1,\n                    Float.NEGATIVE_INFINITY,\n                    Float.POSITIVE_INFINITY,\n                    3 - player,\n                    false,\n                    startTime,\n                    timeLimitMs)',
    '-negamax(\n                    child,\n                    childAcc,\n                    depth - 1,\n                    Float.NEGATIVE_INFINITY,\n                    Float.POSITIVE_INFINITY,\n                    3 - player,\n                    startTime,\n                    timeLimitMs)'
)

text = text.replace(
    'alphaBeta(\n                    child,\n                    childAcc,\n                    depth - 1,\n                    alpha,\n                    alpha + 1.0f,\n                    3 - player,\n                    false,\n                    startTime,\n                    timeLimitMs)',
    '-negamax(\n                    child,\n                    childAcc,\n                    depth - 1,\n                    -alpha - 1.0f,\n                    -alpha,\n                    3 - player,\n                    startTime,\n                    timeLimitMs)'
)

text = text.replace(
    'alphaBeta(\n                      child,\n                      childAcc,\n                      depth - 1,\n                      value,\n                      Float.POSITIVE_INFINITY,\n                      3 - player,\n                      false,\n                      startTime,\n                      timeLimitMs)',
    '-negamax(\n                      child,\n                      childAcc,\n                      depth - 1,\n                      Float.NEGATIVE_INFINITY,\n                      -value,\n                      3 - player,\n                      startTime,\n                      timeLimitMs)'
)


# generateNextBoards signature
text = text.replace(
    'protected List<Board> generateNextBoards(Board board, int player, boolean maximizingPlayer) {',
    'protected List<Board> generateNextBoards(Board board, int player) {'
)

with open("src/main/java/com/engine/nnue_trainer/search/SearchEngine.java", "w") as f:
    f.write(text)
