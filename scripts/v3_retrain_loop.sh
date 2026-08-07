#!/usr/bin/env bash
# v3_retrain_loop.sh — bead nnue-trainer-0dj.8: the seamless v3 retrain pipeline.
# One command takes the bot's real games from the production server to a gauntlet-gated
# nnue_v3_net.json candidate with zero manual steps in between.
#
#   scripts/v3_retrain_loop.sh <workdir> [--threshold 0.45] [--from <stage>] [--until <stage>]
#                              [--deep-depth N]
#
# Stages (each idempotent; a .done.<stage> stamp in <workdir> marks completion, so
# re-running the same command resumes after the last finished stage):
#
#   fetch     copy the prod games.db into <workdir>/games.db. Source is $GAMES_SRC — a local
#             path (default: import_games.py's current source, the sibling virusgame checkout)
#             or "user@host:/path/games.db", which is fetched over scp. The prod hostname is
#             deliberately NOT defaulted here (it is kept out of git; see .claude/skills).
#   dataset   V3SiblingDatasetEmitter <workdir>/games.db -> <workdir>/nnue_v3_siblings.jsonl.
#             With --deep-depth N, V3DeepLabelEmitter is run instead, sharded across all CPUs
#             (CLI: db out depth shardIdx shardCount) and the shards concatenated. train_net's
#             .jsonl->.npz cache is mtime-validated, so a re-emitted dataset re-parses itself.
#   train     python -m python.v3.train_net: one informational sweep per seed in $SEEDS
#             (TEMPO=1 adds --tempo to sweeps AND the export; the runtime loads 1156-wide
#             tempo nets as of 2026-08-07), then the export
#             run (--hidden $HIDDEN --seed $EXPORT_SEED) writes <workdir>/candidate_net.json.
#             The candidate NEVER lands in the repo before the gate passes.
#   verify    regenerate the parity fixture against the candidate, run V3NetParityTest (HARD
#             gate: Java must reproduce the Python forward pass), then V3OrderingProbe with
#             the candidate (INFORMATIONAL ONLY — printed, never gated: six offline-online
#             disconnects in a row taught us offline metrics do not predict strength, see
#             docs/nnue-v3-net.md). The committed fixture is restored afterwards either way;
#             an interrupted verify is healed on the next run from <workdir>/fixture.orig.
#   gauntlet  $GAUNTLET_INSTANCES parallel GauntletV3Run instances, candidate vs HAND_TUNED
#             at fixed depth, per-instance seeds spaced 1000 apart (GauntletMatch derives
#             openings as seed+game/2, so nearby seeds replay overlapping openings — bead
#             nnue-trainer-riy). Defaults pool 4x100 = 400 games, the minimum a ship
#             decision needs (docs/nnue-v3-net.md).
#   report    (always re-runs) pooled W-L-D, p = (W+0.5D)/N, binomial SE = sqrt(p(1-p)/N).
#             SHIP GATE: pooled lower bound p - 2*SE >= --threshold (default 0.45 vs
#             hand-tuned — a distillation that holds ~50% reproduced its teacher). Prints a
#             markdown summary (also <workdir>/report.md). On PASS the candidate + fixture
#             are copied into the repo with commit instructions; this script never commits.
#             Exit 0 = PASS, 1 = FAIL, anything else = a stage error.
#
# Resume semantics: `--from <stage>` clears that stage's stamp and every later one, forcing
# them to re-run (e.g. `--from gauntlet` re-gauntlets an already-trained candidate).
# `--until <stage>` stops after that stage completes (e.g. `--until dataset` to prep data on
# a busy box and train later — the next run resumes where it left off). Deleting the workdir
# starts from scratch. Stamps only ever live in the workdir, never the repo.
#
# Knobs (env): GAMES_SRC, SWEEP="0 16 32 64 128", SEEDS="0 1 2", TEMPO=0, HIDDEN=32,
#   EXPORT_SEED=0, PROBE_POSITIONS=400, GAUNTLET_INSTANCES=4, GAUNTLET_GAMES=100 (per
#   instance), GAUNTLET_DEPTH=3, GAUNTLET_SEED_BASE=7, JAVA_HOME (auto-detected: homebrew
#   openjdk@21 on macOS, /usr/lib/jvm/java-21-openjdk-amd64 on Linux prod), PYTHON
#   (default .venv/bin/python, falling back to python3).
#
# Examples:
#   scripts/v3_retrain_loop.sh /tmp/retrain-$(date +%Y%m%d)
#   GAMES_SRC="user@prodhost:/srv/virusgame/data/games.db" scripts/v3_retrain_loop.sh work/
#   scripts/v3_retrain_loop.sh work/ --from gauntlet --threshold 0.50
set -euo pipefail

usage() {
  sed -n '2,/^set -euo/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'
  exit 1
}

WORK=""
THRESHOLD=0.45
FROM=""
UNTIL=""
DEEP_DEPTH=0
while [ $# -gt 0 ]; do
  case "$1" in
    --threshold) THRESHOLD="$2"; shift 2 ;;
    --from) FROM="$2"; shift 2 ;;
    --until) UNTIL="$2"; shift 2 ;;
    --deep-depth) DEEP_DEPTH="$2"; shift 2 ;;
    -h|--help) usage ;;
    -*) echo "unknown flag: $1"; usage ;;
    *) [ -z "$WORK" ] || usage; WORK="$1"; shift ;;
  esac
done
[ -n "$WORK" ] || usage

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
mkdir -p "$WORK/logs"
WORK="$(cd "$WORK" && pwd)"

# --- environment -----------------------------------------------------------------------
if [ -z "${JAVA_HOME:-}" ]; then
  for j in /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
           /usr/lib/jvm/java-21-openjdk-amd64; do
    if [ -d "$j" ]; then export JAVA_HOME="$j"; break; fi
  done
fi
[ -z "${JAVA_HOME:-}" ] || export PATH="$JAVA_HOME/bin:$PATH"

PY="${PYTHON:-$ROOT/.venv/bin/python}"
[ -x "$PY" ] || PY=python3

: "${GAMES_SRC:=/Users/iv/Projects/virusgame/backend/data/games.db}"
: "${SWEEP:=0 16 32 64 128}"
: "${SEEDS:=0 1 2}"
: "${TEMPO:=0}"
: "${HIDDEN:=32}"
: "${EXPORT_SEED:=0}"
: "${PROBE_POSITIONS:=400}"
: "${GAUNTLET_INSTANCES:=4}"
: "${GAUNTLET_GAMES:=100}"
: "${GAUNTLET_DEPTH:=3}"
: "${GAUNTLET_SEED_BASE:=7}"

DB="$WORK/games.db"
DATASET="$WORK/nnue_v3_siblings.jsonl"
CAND="$WORK/candidate_net.json"
FIXTURE="src/test/resources/v3/net_parity_fixture.json"
TRAIN_PKG=com.engine.nnue_trainer.train
CP=""

die() { echo "ERROR: $*" >&2; exit 2; }

ncpu() { getconf _NPROCESSORS_ONLN 2>/dev/null || nproc; }

# Compile once per run and resolve the bare-java classpath (same recipe as td_retrain_loop.sh).
ensure_cp() {
  [ -z "$CP" ] || return 0
  echo ">> compiling + resolving classpath"
  ./mvnw -q -DskipTests compile
  CP="target/classes:$(./mvnw -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout | tail -1)"
}

# --- resumable stage machinery ---------------------------------------------------------
STAGES="fetch dataset train verify gauntlet report"
stamp() { echo "$WORK/.done.$1"; }

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

# Crash recovery: an interrupted verify leaves the candidate's fixture in the repo — put the
# committed one back before anything else runs.
if [ -f "$WORK/fixture.orig" ]; then
  echo ">> healing interrupted verify: restoring $FIXTURE"
  cp "$WORK/fixture.orig" "$FIXTURE"
  rm -f "$WORK/fixture.orig"
fi

run_stage() {
  local s="$1"
  if [ -f "$(stamp "$s")" ]; then
    echo ">> [$s] already done — skipping (redo with --from $s)"
    return 0
  fi
  echo ">> [$s] === $(date '+%H:%M:%S')"
  "stage_$s"
  touch "$(stamp "$s")"
}

# --- stage 1: fetch --------------------------------------------------------------------
stage_fetch() {
  echo ">> fetching games from: $GAMES_SRC"
  case "$GAMES_SRC" in
    *:*) scp "$GAMES_SRC" "$DB.tmp" ;;    # remote prod dump over scp
    *)   cp "$GAMES_SRC" "$DB.tmp" ;;     # local sibling checkout (import_games.py's source)
  esac
  mv "$DB.tmp" "$DB"
  if command -v sqlite3 >/dev/null 2>&1; then
    sqlite3 "$DB" "SELECT COUNT(*) FROM games WHERE rows=12 AND cols=12;" > "$WORK/corpus_games.txt"
    echo ">> 12x12 games in corpus: $(cat "$WORK/corpus_games.txt")"
  fi
}

# --- stage 2: dataset ------------------------------------------------------------------
stage_dataset() {
  ensure_cp
  if [ "$DEEP_DEPTH" -gt 0 ]; then
    # bead d4a.6.4: deep-search labels. CLI: db out depth shardIdx shardCount.
    [ -f "target/classes/${TRAIN_PKG//./\/}/V3DeepLabelEmitter.class" ] \
      || die "--deep-depth needs $TRAIN_PKG.V3DeepLabelEmitter, which this branch does not have (bead d4a.6.4)"
    local n; n="$(ncpu)"
    echo ">> deep labels at depth $DEEP_DEPTH across $n shards"
    rm -f "$WORK"/deep_shard_*.jsonl
    local pids="" i
    for i in $(seq 0 $((n - 1))); do
      java -cp "$CP" "$TRAIN_PKG.V3DeepLabelEmitter" \
        "$DB" "$WORK/deep_shard_$i.jsonl" "$DEEP_DEPTH" "$i" "$n" \
        > "$WORK/logs/deep_$i.log" 2>&1 &
      pids="$pids $!"
    done
    local p
    for p in $pids; do wait "$p" || die "deep-label shard failed — see $WORK/logs/deep_*.log"; done
    cat "$WORK"/deep_shard_*.jsonl > "$DATASET"
  else
    java -cp "$CP" "$TRAIN_PKG.V3SiblingDatasetEmitter" "$DB" "$DATASET" 2>&1 \
      | tee "$WORK/logs/emit.log"
  fi
  echo ">> dataset rows: $(wc -l < "$DATASET" | tr -d ' ')"
}

# --- stage 3: train --------------------------------------------------------------------
stage_train() {
  local tempo_flag="" s
  # TEMPO=1 trains and EXPORTS 1156-wide tempo nets; the runtime loads them and queries
  # evaluate(board, stm, movesLeft) (merged 2026-08-07).
  [ "$TEMPO" = 1 ] && tempo_flag="--tempo"
  for s in $SEEDS; do
    echo ">> sweep seed $s (H: $SWEEP)${tempo_flag:+ [tempo]}"
    "$PY" -m python.v3.train_net "$DATASET" --sweep $SWEEP --seed "$s" $tempo_flag 2>&1 \
      | tee "$WORK/logs/sweep_seed$s.log"
  done
  echo ">> exporting candidate: H=$HIDDEN seed=$EXPORT_SEED -> $CAND"
  "$PY" -m python.v3.train_net "$DATASET" --hidden "$HIDDEN" --seed "$EXPORT_SEED" $tempo_flag --out "$CAND" 2>&1 \
    | tee "$WORK/logs/export.log"
  [ -s "$CAND" ] || die "training finished but $CAND was not written"
}

# --- stage 4: verify -------------------------------------------------------------------
stage_verify() {
  ensure_cp
  # Parity (HARD gate): regen the fixture against the candidate, run the Java test, restore
  # the committed fixture either way. meta.weights points at the candidate via a repo-root-
  # relative path, which is exactly how V3NetParityTest resolves it.
  cp "$FIXTURE" "$WORK/fixture.orig"
  "$PY" scripts/gen_v3_net_fixture.py --weights "$CAND" --out "$FIXTURE" \
    | tee "$WORK/logs/fixture.log"
  local rc=0
  ./mvnw -q test -Dtest=V3NetParityTest > "$WORK/logs/parity.log" 2>&1 || rc=$?
  cp "$WORK/fixture.orig" "$FIXTURE"
  rm -f "$WORK/fixture.orig"
  if [ "$rc" -ne 0 ]; then
    tail -40 "$WORK/logs/parity.log"
    die "V3NetParityTest FAILED against the candidate (full log: $WORK/logs/parity.log)"
  fi
  echo ">> parity PASS"

  # Ordering probe: INFORMATIONAL ONLY. Offline ordering never gates a ship decision — only
  # the gauntlet win-rate does (docs/nnue-v3-net.md, six offline-online disconnects).
  echo ">> ordering probe ($PROBE_POSITIONS positions) — informational, not gated"
  V3EVAL=net NNUEV3NET_WEIGHTS="$CAND" \
    java -cp "$CP" "$TRAIN_PKG.V3OrderingProbe" "$DB" "$PROBE_POSITIONS" 2>&1 \
    | tee "$WORK/logs/probe.log"
}

# --- stage 5: gauntlet -----------------------------------------------------------------
stage_gauntlet() {
  ensure_cp
  echo ">> $GAUNTLET_INSTANCES parallel gauntlets x $GAUNTLET_GAMES games, depth $GAUNTLET_DEPTH, seeds spaced 1000"
  local pids="" i seed
  for i in $(seq 0 $((GAUNTLET_INSTANCES - 1))); do
    # ponytail: seeds spaced 1000 apart because GauntletMatch openings are seed+game/2
    # (bead nnue-trainer-riy); switch to hashed seeds once that fix merges.
    seed=$((GAUNTLET_SEED_BASE + i * 1000))
    V3EVAL=net NNUEV3NET_WEIGHTS="$CAND" MATCHUP=bar \
      java -cp "$CP" "$TRAIN_PKG.GauntletV3Run" "$GAUNTLET_GAMES" "$GAUNTLET_DEPTH" "$seed" \
      > "$WORK/logs/gauntlet_$i.log" 2>&1 &
    pids="$pids $!"
    echo "   instance $i: seed $seed -> $WORK/logs/gauntlet_$i.log"
  done
  local p
  for p in $pids; do wait "$p" || die "a gauntlet instance failed — see $WORK/logs/gauntlet_*.log"; done
  grep -h "v3 vs HAND_TUNED" "$WORK"/logs/gauntlet_*.log
}

# --- stage 6: report + ship gate -------------------------------------------------------
stage_report() {
  local wld w l d
  wld="$(grep -h "v3 vs HAND_TUNED" "$WORK"/logs/gauntlet_*.log 2>/dev/null \
    | grep -Eo '[0-9]+-[0-9]+-[0-9]+' \
    | awk -F- '{w+=$1; l+=$2; d+=$3} END {print w+0, l+0, d+0}' || true)"
  [ -n "$wld" ] || wld="0 0 0"
  read -r w l d <<EOF
$wld
EOF
  [ $((w + l + d)) -gt 0 ] || die "no gauntlet results found in $WORK/logs/gauntlet_*.log"

  local gate n p se lb verdict
  gate="$(awk -v w="$w" -v l="$l" -v d="$d" -v t="$THRESHOLD" 'BEGIN {
    n = w + l + d; p = (w + 0.5 * d) / n; se = sqrt(p * (1 - p) / n); lb = p - 2 * se;
    printf "%d %.4f %.4f %.4f %s", n, p, se, lb, (lb >= t ? "PASS" : "FAIL") }')"
  read -r n p se lb verdict <<EOF
$gate
EOF

  local corpus="?" rows="?" holdout="?"
  [ -f "$WORK/corpus_games.txt" ] && corpus="$(cat "$WORK/corpus_games.txt")"
  [ -f "$DATASET" ] && rows="$(wc -l < "$DATASET" | tr -d ' ')"
  holdout="$("$PY" -c "import json,sys; m=json.load(open(sys.argv[1]))['meta']; \
print('H=%d top1=%.3f rho=%.3f r2=%.3f (games train/holdout %d/%d)' % (m['hidden'], \
m['top1_holdout'], m['spearman_holdout'], m['r2_holdout'], m['games_train'], m['games_holdout']))" \
    "$CAND" 2>/dev/null || echo "?")"

  {
    echo "# v3 retrain report — $(date '+%Y-%m-%d %H:%M')"
    echo
    echo "| item | value |"
    echo "|---|---|"
    echo "| corpus (12x12 games) | $corpus |"
    echo "| dataset rows | $rows |"
    echo "| candidate | \`$CAND\` |"
    echo "| training holdout | $holdout |"
    echo "| parity (V3NetParityTest) | PASS |"
    echo "| ordering probe (informational) | see below — never gates the ship |"
    echo "| gauntlet | ${GAUNTLET_INSTANCES}x${GAUNTLET_GAMES} games, depth $GAUNTLET_DEPTH, seeds spaced 1000 |"
    echo "| pooled W-L-D (vs HAND_TUNED) | $w-$l-$d of $n |"
    echo "| win rate p (draws = 0.5) | $p, SE $se, p - 2SE = $lb |"
    echo "| ship gate (lower bound >= $THRESHOLD) | **$verdict** |"
    echo
    echo "## probe"
    echo '```'
    grep -E "top-1|Spearman|median" "$WORK/logs/probe.log" 2>/dev/null || echo "probe log missing"
    echo '```'
  } | tee "$WORK/report.md"

  if [ "$verdict" = PASS ]; then
    cp "$CAND" nnue_v3_net.json
    "$PY" scripts/gen_v3_net_fixture.py > "$WORK/logs/fixture_ship.log"  # against repo nnue_v3_net.json
    echo
    echo ">> GATE PASS — candidate copied into the repo. To ship, review and commit:"
    echo "     git add nnue_v3_net.json $FIXTURE"
    echo "     git commit  # this script never commits"
    exit 0
  fi
  echo
  echo ">> GATE FAIL — candidate stays in $WORK, repo untouched."
  exit 1
}

for s in fetch dataset train verify gauntlet; do
  run_stage "$s"
  if [ "$s" = "$UNTIL" ]; then
    echo ">> stopped after [$s] (--until); re-run without --until to continue"
    exit 0
  fi
done
echo ">> [report] === $(date '+%H:%M:%S')"
stage_report
