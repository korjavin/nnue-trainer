#!/usr/bin/env bash
# mcts_selfplay_gen.sh — plan 20260807-mcts-az-feasibility Phase 2: one self-play RL generation.
# self-play -> train -> net-vs-net gauntlet gate -> promote on >=55%.
#
#   scripts/mcts_selfplay_gen.sh <workdir> [--games N] [--sims N] [--gate-games N]
#                                [--gate-sims N] [--epochs N] [--from <stage>] [--until <stage>]
#
# One invocation runs ONE generation. State in <workdir>:
#   champion.json   the current best artifact (initialized from the repo's mcts_policy.json,
#                   i.e. the Phase 1 gen-0 policy prior; it has no value head, so its MCTS
#                   side falls back to the hand-tuned leaf value — by design)
#   gen             the generation number about to run (starts at 1)
#   gen<N>/         per-generation workspace: self-play shards, candidate.json, logs, stamps
#
# Stages (idempotent; a .done.<stage> stamp inside gen<N>/ marks completion, so re-running
# the same command resumes after the last finished stage — the v3_retrain_loop.sh pattern):
#
#   selfplay  SelfPlayMcts sharded across all CPUs with the champion artifact (MCTS_VALUE=net:
#             the value head is used once the champion has one; gen 1 runs hand-tuned value).
#             Deterministic per (seed, shard); seed = SEED_BASE + gen*1000 (bead riy spacing).
#   train     train_selfplay.py on a sliding window of the last $WINDOW generations' rows
#             (plan Phase 2 task 3) -> gen<N>/candidate.json (+ holdout top-1 / value MAE).
#   gauntlet  $GATE_INSTANCES parallel GauntletMctsRun candidate-vs-champion at $GATE_SIMS
#             fixed sims (MCTS_PRIOR_B mode), per-instance seeds spaced 1000.
#   report    (always re-runs) pools W-L-D; gate: (W + 0.5D)/N >= $GATE (0.55, ~2 SE at 400
#             games — mirrors GauntletMctsRun.promote, which prints the per-instance verdict).
#             On PROMOTE: candidate -> champion.json (archived as champion_gen<N>.json), gen
#             incremented — the next invocation runs the next generation. On KEEP: champion
#             stays, gen still increments (a fresh generation gets fresh self-play seeds).
#             Exit 0 = PROMOTE, 1 = KEEP, anything else = stage error.
#
# Knobs (env): GAMES=192 (self-play games/gen), SIMS=256 (self-play sims/move),
#   GATE_GAMES=100 (per instance), GATE_INSTANCES=4 (pooled 400 = the plan's gate size),
#   GATE_SIMS=256, GATE=0.55, EPOCHS=8, WINDOW=3, SEED_BASE=11, MCTS_CPUCT, JAVA_HOME
#   (auto-detected), PYTHON (default .venv/bin/python).
#
# Generation 1 example:
#   scripts/mcts_selfplay_gen.sh work/mcts-rl
set -euo pipefail

usage() {
  sed -n '2,/^set -euo/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'
  exit 1
}

WORK=""
FROM=""
UNTIL=""
while [ $# -gt 0 ]; do
  case "$1" in
    --games) GAMES="$2"; shift 2 ;;
    --sims) SIMS="$2"; shift 2 ;;
    --gate-games) GATE_GAMES="$2"; shift 2 ;;
    --gate-sims) GATE_SIMS="$2"; shift 2 ;;
    --epochs) EPOCHS="$2"; shift 2 ;;
    --from) FROM="$2"; shift 2 ;;
    --until) UNTIL="$2"; shift 2 ;;
    -h|--help) usage ;;
    -*) echo "unknown flag: $1"; usage ;;
    *) [ -z "$WORK" ] || usage; WORK="$1"; shift ;;
  esac
done
[ -n "$WORK" ] || usage

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
mkdir -p "$WORK"
WORK="$(cd "$WORK" && pwd)"

# --- environment (same recipe as v3_retrain_loop.sh) -----------------------------------
if [ -z "${JAVA_HOME:-}" ]; then
  for j in /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
           /usr/lib/jvm/java-21-openjdk-amd64; do
    if [ -d "$j" ]; then export JAVA_HOME="$j"; break; fi
  done
fi
[ -z "${JAVA_HOME:-}" ] || export PATH="$JAVA_HOME/bin:$PATH"
PY="${PYTHON:-$ROOT/.venv/bin/python}"
[ -x "$PY" ] || PY=python3

: "${GAMES:=192}"
: "${SIMS:=256}"
: "${GATE_GAMES:=100}"
: "${GATE_INSTANCES:=4}"
: "${GATE_SIMS:=256}"
: "${GATE:=0.55}"
: "${EPOCHS:=8}"
: "${WINDOW:=3}"
: "${SEED_BASE:=11}"
: "${MCTS_CPUCT:=1.5}"

CHAMPION="$WORK/champion.json"
[ -f "$CHAMPION" ] || cp "$ROOT/mcts_policy.json" "$CHAMPION"
[ -f "$WORK/gen" ] || echo 1 > "$WORK/gen"
GEN="$(cat "$WORK/gen")"
GDIR="$WORK/gen$GEN"
mkdir -p "$GDIR/logs"
CAND="$GDIR/candidate.json"
MCTS_PKG=com.engine.nnue_trainer.mcts
TRAIN_PKG=com.engine.nnue_trainer.train
CP=""

die() { echo "ERROR: $*" >&2; exit 2; }
ncpu() { getconf _NPROCESSORS_ONLN 2>/dev/null || nproc; }

ensure_cp() {
  [ -z "$CP" ] || return 0
  echo ">> compiling + resolving classpath"
  ./mvnw -q -DskipTests compile
  CP="target/classes:$(./mvnw -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout | tail -1)"
}

# --- resumable stage machinery ---------------------------------------------------------
STAGES="selfplay train gauntlet report"
stamp() { echo "$GDIR/.done.$1"; }

if [ -n "$UNTIL" ]; then
  case " $STAGES " in *" $UNTIL "*) ;; *) die "--until $UNTIL: not one of: $STAGES" ;; esac
fi
if [ -n "$FROM" ]; then
  case " $STAGES " in *" $FROM "*) ;; *) die "--from $FROM: not one of: $STAGES" ;; esac
  clear=0
  for s in $STAGES; do
    [ "$s" = "$FROM" ] && clear=1
    [ "$clear" = 1 ] && rm -f "$(stamp "$s")"
  done
fi

run_stage() {
  local s="$1"
  if [ -f "$(stamp "$s")" ]; then
    echo ">> [gen $GEN / $s] already done — skipping (redo with --from $s)"
    return 0
  fi
  echo ">> [gen $GEN / $s] === $(date '+%H:%M:%S')"
  "stage_$s"
  touch "$(stamp "$s")"
}

# --- stage 1: self-play ----------------------------------------------------------------
stage_selfplay() {
  ensure_cp
  local n seed pids i
  n="$(ncpu)"
  seed=$((SEED_BASE + GEN * 1000))
  echo ">> $GAMES self-play games, $SIMS sims/move, seed $seed, $n shards, champion=$CHAMPION"
  rm -f "$GDIR"/selfplay_shard_*.jsonl
  pids=""
  for i in $(seq 0 $((n - 1))); do
    MCTS_PRIOR="$CHAMPION" MCTS_VALUE=net MCTS_CPUCT="$MCTS_CPUCT" \
      java -cp "$CP" "$MCTS_PKG.SelfPlayMcts" \
      "$GDIR/selfplay_shard_$i.jsonl" "$GAMES" "$SIMS" "$i" "$n" "$seed" \
      > "$GDIR/logs/selfplay_$i.log" 2>&1 &
    pids="$pids $!"
  done
  local p
  for p in $pids; do wait "$p" || die "self-play shard failed — see $GDIR/logs/selfplay_*.log"; done
  cat "$GDIR"/selfplay_shard_*.jsonl > "$GDIR/selfplay.jsonl"
  echo ">> rows: $(wc -l < "$GDIR/selfplay.jsonl" | tr -d ' ')"
  grep -h "^shard" "$GDIR"/logs/selfplay_*.log || true
}

# --- stage 2: train --------------------------------------------------------------------
stage_train() {
  # Sliding window: this generation plus up to WINDOW-1 previous ones (plan Phase 2 task 3).
  local datasets="" g
  for g in $(seq $((GEN - WINDOW + 1)) "$GEN"); do
    [ "$g" -ge 1 ] && [ -f "$WORK/gen$g/selfplay.jsonl" ] && datasets="$datasets $WORK/gen$g/selfplay.jsonl"
  done
  [ -n "$datasets" ] || die "no self-play datasets found for generations <= $GEN"
  echo ">> training on:$datasets"
  # shellcheck disable=SC2086
  "$PY" python/mcts/train_selfplay.py $datasets --out "$CAND" --epochs "$EPOCHS" 2>&1 \
    | tee "$GDIR/logs/train.log"
  [ -s "$CAND" ] || die "training finished but $CAND was not written"
}

# --- stage 3: gauntlet (candidate vs champion, fixed sims) -----------------------------
stage_gauntlet() {
  ensure_cp
  # Stale logs from a previous run with a higher GATE_INSTANCES would get pooled by stage_report.
  rm -f "$GDIR"/logs/gauntlet_*.log
  echo ">> $GATE_INSTANCES x $GATE_GAMES games, candidate vs champion, $GATE_SIMS sims, seeds spaced 1000"
  local pids="" i seed
  for i in $(seq 0 $((GATE_INSTANCES - 1))); do
    seed=$((SEED_BASE + GEN * 10000 + i * 1000))
    MCTS_PRIOR="$CAND" MCTS_PRIOR_B="$CHAMPION" MCTS_VALUE=net \
      MCTS_CPUCT="$MCTS_CPUCT" MCTS_GATE="$GATE" \
      java -cp "$CP" "$TRAIN_PKG.GauntletMctsRun" "$GATE_GAMES" 0 "$seed" "$GATE_SIMS" \
      > "$GDIR/logs/gauntlet_$i.log" 2>&1 &
    pids="$pids $!"
    echo "   instance $i: seed $seed -> $GDIR/logs/gauntlet_$i.log"
  done
  local p
  for p in $pids; do wait "$p" || die "a gauntlet instance failed — see $GDIR/logs/gauntlet_*.log"; done
  grep -h "W-L-D" "$GDIR"/logs/gauntlet_*.log
}

# --- stage 4: report + promotion gate --------------------------------------------------
stage_report() {
  local wld w l d
  wld="$(grep -h "W-L-D" "$GDIR"/logs/gauntlet_*.log 2>/dev/null \
    | grep -Eo '[0-9]+-[0-9]+-[0-9]+' \
    | awk -F- '{w+=$1; l+=$2; d+=$3} END {print w+0, l+0, d+0}' || true)"
  [ -n "$wld" ] || wld="0 0 0"
  read -r w l d <<EOF
$wld
EOF
  [ $((w + l + d)) -gt 0 ] || die "no gauntlet results found in $GDIR/logs/gauntlet_*.log"

  # Pooled gate — same arithmetic as GauntletMctsRun.promote (JUnit-pinned): (W+0.5D)/N >= GATE.
  local verdict n p
  read -r n p verdict <<EOF
$(awk -v w="$w" -v l="$l" -v d="$d" -v t="$GATE" 'BEGIN {
    n = w + l + d; p = (w + 0.5 * d) / n;
    printf "%d %.4f %s", n, p, (p >= t ? "PROMOTE" : "KEEP") }')
EOF

  echo
  echo "=== gen $GEN report: candidate $w-$l-$d of $n vs champion, p=$p, gate $GATE -> $verdict ==="
  echo "$(date '+%Y-%m-%d %H:%M') gen $GEN: $w-$l-$d p=$p $verdict" >> "$WORK/history.log"

  echo $((GEN + 1)) > "$WORK/gen"
  if [ "$verdict" = PROMOTE ]; then
    cp "$CAND" "$WORK/champion_gen$GEN.json"
    cp "$CAND" "$CHAMPION"
    echo ">> PROMOTED: $CHAMPION is now the gen-$GEN candidate (archived champion_gen$GEN.json)"
    exit 0
  fi
  echo ">> KEEP: champion unchanged; next run plays generation $((GEN + 1)) with fresh seeds"
  exit 1
}

for s in selfplay train gauntlet; do
  run_stage "$s"
  if [ "$s" = "$UNTIL" ]; then
    echo ">> stopped after [$s] (--until); re-run without --until to continue"
    exit 0
  fi
done
echo ">> [gen $GEN / report] === $(date '+%H:%M:%S')"
stage_report
