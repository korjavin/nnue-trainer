#!/usr/bin/env bash
# One TD-leaf pass: warm-started self-play -> dataset.json -> train.py -> nnue_weights.json.
# This is value iteration by hand: run it, then re-run to bootstrap off the freshly trained net.
#
# Knobs via env (small defaults = a fast smoke run, NOT a strength run):
#   NUM_GAMES=10  SEARCH_DEPTH=2  TD_LAMBDA=0.5  SEED=1  MAX_TURNS=100
#   OUT=dataset.json   # self-play dataset AND train.py's input
# LABEL_MODE is forced to TD_LEAF here; the OUTCOME baseline is the plain generator default.
set -euo pipefail
cd "$(dirname "$0")"

# JDK21 is required by mvnw but may not be on PATH (see eval-vs-gobot skill).
if [ -d /usr/lib/jvm/java-21-openjdk-amd64 ]; then
  export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

: "${NUM_GAMES:=10}"
: "${SEARCH_DEPTH:=2}"
: "${TD_LAMBDA:=0.5}"
: "${SEED:=1}"
: "${MAX_TURNS:=100}"
: "${OUT:=dataset.json}"
export LABEL_MODE=TD_LEAF NUM_GAMES SEARCH_DEPTH TD_LAMBDA SEED MAX_TURNS OUT

echo ">> compiling"
./mvnw -q -DskipTests compile

echo ">> self-play (TD_LEAF, warm-started from nnue_weights.json) -> $OUT"
CP="target/classes:$(./mvnw -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout | tail -1)"
java -cp "$CP" com.engine.nnue_trainer.train.SelfPlayGenerator

echo ">> training -> src/main/resources/nnue_weights.json"
PY="${PYTHON:-.venv/bin/python}"
[ -x "$PY" ] || PY=python3
DATASET="$OUT" "$PY" train.py
echo ">> done. Re-run this script to iterate (each pass bootstraps off the new net)."
