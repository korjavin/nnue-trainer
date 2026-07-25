#!/usr/bin/env bash
# bead d4a.4.5 — generate a 12x12-HEAVY v2 raw-position corpus with a DEEP hand-tuned search
# target (TDLEAF_DEPTH=6). Each emitted line carries both `search_score` (RAW centipawn-like
# deep-search value, the d4a.4.5 regression target) and `search_wdl` (legacy logistic squash, for
# the baseline model). Gauntlet is 12x12, so 12x12 is the majority of positions here.
#
# Output is gitignored (python/v2/corpus/*.jsonl). Regenerate with this script; it is a CPU job
# (~45-60 min at depth 6), run detached — NOT under ralphex.
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -d /usr/lib/jvm/java-21-openjdk-amd64 ]; then
  export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

: "${MAX_TURNS:=100}"
: "${BASE_SEED:=101}"
: "${TDLEAF_DEPTH:=6}"
: "${OUT:=python/v2/corpus/corpus_rawscore.jsonl}"

# "ROWS COLS GAMES" per line — 12x12 dominates so it is the majority of positions.
CONFIGS=(
  "12 12 70"
  "9 9 22"
  "7 7 18"
  "5 5 14"
)

echo ">> compiling"
./mvnw -q -DskipTests compile
CP="target/classes:$(./mvnw -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout | tail -1)"

mkdir -p "$(dirname "$OUT")"
: > "$OUT"

i=0
total=0
for cfg in "${CONFIGS[@]}"; do
  read -r ROWS COLS GAMES <<< "$cfg"
  seed=$(( BASE_SEED + i ))
  part="$OUT.${ROWS}x${COLS}.part"
  echo ">> self-play ${ROWS}x${COLS} (seed=$seed, games=$GAMES, depth=$TDLEAF_DEPTH) -> $part"
  EMIT=raw RAW_OUT="$part" ROWS="$ROWS" COLS="$COLS" SEED="$seed" \
    NUM_GAMES="$GAMES" MAX_TURNS="$MAX_TURNS" TDLEAF_DEPTH="$TDLEAF_DEPTH" \
    java -cp "$CP" com.engine.nnue_trainer.train.SelfPlayGenerator
  n=$(wc -l < "$part")
  echo "   ${ROWS}x${COLS}: $n positions"
  cat "$part" >> "$OUT"
  rm -f "$part"
  total=$(( total + n ))
  i=$(( i + 1 ))
done

echo ">> corpus: $total positions -> $OUT"
