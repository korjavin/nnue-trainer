#!/usr/bin/env bash
# Phase 3 Task 3: the automated continuous retraining loop. Each generation:
#   1. self-play + train a CHALLENGER (warm-started from the current champion) via
#      td_leaf_pass_gobot.sh, writing the challenger weights to $CHALLENGER (NOT the champion).
#   2. RetrainGate plays the offline net-vs-net matches and promotes the challenger ONLY if it
#      beats the champion by PROMOTE_MARGIN and does not fall below the hand-tuned bar — otherwise
#      the champion (src/main/resources/nnue_weights.json) is left untouched.
#   3. append a per-generation line (gen, val MSE, W-L vs champion, promoted?) to the run log.
# The gate is deterministic and offline, so a generation is fast; it can NEVER ship a regression.
#
# Maintainer command (long strength run):
#   GENERATIONS=20 NUM_GAMES=200 EPSILON=0.15 EXPLORE_TURNS=8 ./td_retrain_loop.sh
#
# Knobs (env):
#   GENERATIONS=1            # how many generations to attempt
#   MAX_WALL_SECONDS=3600    # hard budget guard: stop before starting a gen past this wall-clock
#   PROMOTE_MARGIN=2         # challenger must win the gate match by (wins-losses) >= this
#   GAUNTLET_GAMES=8         # games per gate match (challenger-vs-champion / vs hand-tuned bar)
#   GAUNTLET_NODE_LIMIT=60000
#   LIVE_SANITY_EVERY=0      # >0: every K gens also run the slow live vs-GoBot check and log it
#   LIVE_SANITY_GAMES=20     # games for that live check (needs sibling ../virusgame; see skill)
#   CHAMPION=src/main/resources/nnue_weights.json   RUN_LOG=champions/run.log
#   SEED_BASE=1              # self-play seed is SEED_BASE+gen, so each generation is a DISTINCT
#                           # dataset even while the champion is unchanged (defaults to $SEED or 1)
#   Self-play knobs pass straight through to td_leaf_pass_gobot.sh: NUM_GAMES, EPSILON,
#   EXPLORE_TURNS, GOBOT_NODE_LIMIT, TD_LAMBDA, MAX_TURNS ...
# Read the run log with: cat champions/run.log ; history detail in champions/history.log
set -euo pipefail
cd "$(dirname "$0")"

if [ -d /usr/lib/jvm/java-21-openjdk-amd64 ]; then
  export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

: "${GENERATIONS:=1}"
: "${MAX_WALL_SECONDS:=3600}"
: "${LIVE_SANITY_EVERY:=0}"
: "${LIVE_SANITY_GAMES:=20}"
: "${CHAMPION:=src/main/resources/nnue_weights.json}"
: "${CHALLENGER:=target/challenger.json}"
: "${RUN_LOG:=champions/run.log}"
: "${HISTORY_LOG:=champions/history.log}"
: "${SEED_BASE:=${SEED:-1}}"  # per-gen self-play seed = SEED_BASE+gen (distinct dataset each gen)
# PROMOTE_MARGIN / GAUNTLET_* are read by the RetrainGate java child; command-line-prefixed env
# vars are already exported to children, so nothing to re-export here.

mkdir -p "$(dirname "$RUN_LOG")" "$(dirname "$CHALLENGER")"

# Per-run temp log (mktemp, not a fixed /tmp path): a hardcoded world-writable path lets tee follow
# a pre-planted symlink (CWE-377) and clobbers between concurrent runs. Cleaned up on exit.
GEN_LOG="$(mktemp "${TMPDIR:-/tmp}/td_retrain_gen.XXXXXX.log")"
trap 'rm -f "$GEN_LOG"' EXIT

echo ">> compiling + resolving classpath"
./mvnw -q -DskipTests compile
CP="target/classes:$(./mvnw -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout | tail -1)"

start=$SECONDS
for gen in $(seq 1 "$GENERATIONS"); do
  elapsed=$((SECONDS - start))
  if [ "$elapsed" -ge "$MAX_WALL_SECONDS" ]; then
    echo ">> budget guard: ${elapsed}s >= MAX_WALL_SECONDS=${MAX_WALL_SECONDS}s — stopping before gen $gen"
    break
  fi

  echo ">> === generation $gen/$GENERATIONS (elapsed ${elapsed}s) ==="
  # Train the challenger to $CHALLENGER; OUT_PATH points train.py there so the champion is untouched.
  # A per-gen SEED makes each rejected generation a DISTINCT attempt (self-play/train are otherwise
  # deterministic, so a fixed seed would regenerate the same rejected challenger every generation).
  SEED="$((SEED_BASE + gen))" OUT_PATH="$CHALLENGER" ./td_leaf_pass_gobot.sh 2>&1 | tee "$GEN_LOG"
  # `|| true`: under set -euo pipefail a no-match grep would abort the whole loop before the
  # `${val:=?}` fallback runs. A missing val MSE line is non-fatal — log '?' and gate anyway.
  val=$(grep -oE 'val MSE [0-9.]+' "$GEN_LOG" | tail -1 | awk '{print $3}' || true)
  : "${val:=?}"

  # Gate: RetrainGate promotes (exit 10) or keeps (exit 0); it never throws on a normal outcome.
  set +e
  gateout=$(java -cp "$CP" com.engine.nnue_trainer.train.RetrainGate \
    "$gen" "$CHALLENGER" "$CHAMPION" "$HISTORY_LOG")
  code=$?
  set -e
  echo "$gateout"
  if [ "$code" -eq 10 ]; then promoted=yes; elif [ "$code" -eq 0 ]; then promoted=no; else
    echo ">> RetrainGate failed (exit $code)"; exit "$code"
  fi

  wl=$(echo "$gateout" | grep -oE 'vsChampion=\[[^]]*\]' | head -1 || true)
  echo "gen=$gen valMSE=$val ${wl:-vsChampion=?} promoted=$promoted" | tee -a "$RUN_LOG"

  # Optional slow live vs-GoBot sanity (off by default): the offline gate is the real per-gen
  # decision; this is a periodic reality-check on the deployed champion only.
  if [ "$LIVE_SANITY_EVERY" -gt 0 ] && [ $((gen % LIVE_SANITY_EVERY)) -eq 0 ]; then
    if [ -f eval_java_vs_go.py ]; then
      echo ">> live sanity: champion vs GoBot ($LIVE_SANITY_GAMES games) — slow"
      sanity=$(python3 eval_java_vs_go.py "$LIVE_SANITY_GAMES" 2>&1 | tail -1 || true)
      echo "gen=$gen liveSanity: $sanity" | tee -a "$RUN_LOG"
    else
      echo ">> live sanity requested but eval_java_vs_go.py missing — skipping" | tee -a "$RUN_LOG"
    fi
  fi
done

echo ">> loop done. run log: $RUN_LOG"
