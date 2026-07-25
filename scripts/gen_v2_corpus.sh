#!/usr/bin/env bash
# Generate a multi-board-size v2 raw-position corpus (JSONL) via SelfPlayGenerator EMIT=raw.
# Loops a fixed set of board sizes, one self-play run each, concatenating every per-size
# JSONL into one corpus file. Deterministic: SEED is offset per size (BASE_SEED + i).
#
# Knobs via env (small defaults = a fast smoke run, NOT a full corpus run):
#   NUM_GAMES=20  MAX_TURNS=100  BASE_SEED=1
#   OUT=python/v2/corpus/corpus.jsonl   # concatenated corpus (gitignored)
# The actual large corpus is a CPU job run outside ralphex — bump NUM_GAMES and re-run.
set -euo pipefail
cd "$(dirname "$0")/.."

# JDK21 is required by mvnw but may not be on PATH (see eval-vs-gobot skill).
if [ -d /usr/lib/jvm/java-21-openjdk-amd64 ]; then
  export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

: "${NUM_GAMES:=20}"
: "${MAX_TURNS:=100}"
: "${BASE_SEED:=1}"
: "${OUT:=python/v2/corpus/corpus.jsonl}"

# Board sizes to sweep: "ROWS COLS" per line.
SIZES=( "12 12" "9 9" "7 7" "5 5" "5 7" )

echo ">> compiling"
./mvnw -q -DskipTests compile
CP="target/classes:$(./mvnw -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout | tail -1)"

mkdir -p "$(dirname "$OUT")"
: > "$OUT"   # truncate: corpus is rebuilt from scratch each run

i=0
total=0
for size in "${SIZES[@]}"; do
  read -r ROWS COLS <<< "$size"
  seed=$(( BASE_SEED + i ))
  part="$OUT.$ROWS"x"$COLS.part"
  echo ">> self-play ${ROWS}x${COLS} (seed=$seed, games=$NUM_GAMES) -> $part"
  EMIT=raw RAW_OUT="$part" ROWS="$ROWS" COLS="$COLS" SEED="$seed" \
    NUM_GAMES="$NUM_GAMES" MAX_TURNS="$MAX_TURNS" \
    java -cp "$CP" com.engine.nnue_trainer.train.SelfPlayGenerator
  n=$(wc -l < "$part")
  echo "   ${ROWS}x${COLS}: $n positions"
  cat "$part" >> "$OUT"
  rm -f "$part"
  total=$(( total + n ))
  i=$(( i + 1 ))
done

echo ">> corpus: $total positions across ${#SIZES[@]} board sizes -> $OUT"
