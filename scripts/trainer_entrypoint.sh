#!/usr/bin/env bash
# trainer_entrypoint.sh — the training sidecar's container loop (git-ops: shipped by image
# push, and it NEVER commits or pushes from the container).
#
# Each scheduled window (TRAIN_SCHEDULE_HOUR, default 03:00) runs ONE unit of work:
#   TRAINER_LOOP=mcts (default)  one self-play RL generation via mcts_selfplay_gen.sh
#   TRAINER_LOOP=v3              one NNUE retrain via v3_retrain_loop.sh (dated workdir)
# Stamps inside $WORK make interrupted runs resumable. An overrunning run is never killed —
# when it finishes, the loop just sleeps until the NEXT window.
#
# Fresh-games guardrail: before a run, the prod games DB (GAMES_URL + its -wal sidecar,
# the v3_retrain_loop fetch recipe) is downloaded and games newer than the stored
# watermark are counted. TRAINER_LOOP=v3 skips the run when new < MIN_NEW_GAMES —
# retraining on an unchanged corpus is wasted CPU. For mcts the same check applies only
# when RL_REQUIRE_NEW_GAMES=1: self-play generates its own data, so there the guardrail
# is optional frugality, not correctness. The watermark advances only after a run that
# actually consumed the games.
#
# Human-games curriculum: the mcts loop runs with CURRICULUM=1 by default — the guardrail's
# freshly fetched games.db feeds HumanCurriculumEmitter --human-only (expert-iteration
# targets on human-reached positions, oversampled x$CURRICULUM_REPEAT at train time; see
# mcts_selfplay_gen.sh). The DB is fetched for this even when RL_REQUIRE_NEW_GAMES=0, but a
# failed fetch or few new games never skips the RL run — the stage just runs without/with
# stale data. Set CURRICULUM=0 to turn it off.
#
# Promotions land in $WORK/out (artifact + md report) for the owner to review and commit
# into the repo by hand — a candidate never auto-ships.
#
# Knobs (env): WORK=/work, TRAIN_SCHEDULE_HOUR=3, TRAINER_LOOP=mcts, NICE_LEVEL=10,
#   GAMES=1000, SIMS=192 (modest 8-shared-core server defaults; everything else the
#   underlying scripts read — GATE_*, EPOCHS, WINDOW, SEEDS, HIDDEN, ... — passes through),
#   GAMES_URL=https://vs.wandergeek.org/data/games.db, MIN_NEW_GAMES=25,
#   RL_REQUIRE_NEW_GAMES=0, TRAIN_ON_START=0 (1 = run immediately, then fall into the
#   schedule; handy for smoke tests).
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

: "${WORK:=/work}"
: "${TRAIN_SCHEDULE_HOUR:=3}"
: "${TRAINER_LOOP:=mcts}"
: "${NICE_LEVEL:=10}"
: "${GAMES_URL:=https://vs.wandergeek.org/data/games.db}"
: "${MIN_NEW_GAMES:=25}"
: "${RL_REQUIRE_NEW_GAMES:=0}"
export GAMES="${GAMES:-1000}"
export SIMS="${SIMS:-192}"
# Server default ON: every window has a fresh guardrail DB to mine human games from.
export CURRICULUM="${CURRICULUM:-1}"

GUARD="$WORK/guard"
WM_FILE="$GUARD/watermark"
mkdir -p "$WORK/out" "$GUARD"

log_run() { echo "$(date '+%Y-%m-%d %H:%M') $*" | tee -a "$WORK/runs.log"; }

sleep_until_window() {
  local now target
  now=$(date +%s)
  target=$(date -d "today ${TRAIN_SCHEDULE_HOUR}:00" +%s)
  [ "$target" -gt "$now" ] || target=$(date -d "tomorrow ${TRAIN_SCHEDULE_HOUR}:00" +%s)
  echo ">> sleeping $(((target - now) / 60)) min until $(date -d "@$target" '+%Y-%m-%d %H:%M')"
  sleep $((target - now))
}

# --- fresh-games guardrail -------------------------------------------------------------
# Sets NEW_GAMES + PENDING_WM; returns non-zero if the DB could not be fetched/queried.
# ponytail: watermark is max(started_at), not a row-count high-water mark — a count
# regresses when prod prunes games or the WAL fetch is stale (undercount would wedge the
# loop shut); started_at only ever moves forward, and a stale WAL merely undercounts NEW
# games, which fails safe (skip tonight, catch up tomorrow).
guard_check() {
  NEW_GAMES=""
  PENDING_WM=""
  curl -fsSL "$GAMES_URL" -o "$GUARD/games.db.tmp" || return 1
  # The WAL sidecar holds the newest games (the DB alone checkpoints rarely); optional,
  # so no -S: a missing sidecar is normal right after a checkpoint.
  curl -fsL "$GAMES_URL-wal" -o "$GUARD/games.db.tmp-wal" || rm -f "$GUARD/games.db.tmp-wal"
  rm -f "$GUARD/games.db" "$GUARD/games.db-wal"
  mv "$GUARD/games.db.tmp" "$GUARD/games.db"
  [ ! -f "$GUARD/games.db.tmp-wal" ] || mv "$GUARD/games.db.tmp-wal" "$GUARD/games.db-wal"
  # Fold the WAL into the DB file so consumers that copy only games.db see everything.
  sqlite3 "$GUARD/games.db" "PRAGMA wal_checkpoint(TRUNCATE);" > /dev/null 2>&1 || true
  local wm
  wm="$(cat "$WM_FILE" 2> /dev/null || echo 1970-01-01)"
  NEW_GAMES="$(sqlite3 "$GUARD/games.db" \
    "SELECT COUNT(*) FROM games WHERE rows=12 AND cols=12 AND started_at > '$wm';")" || return 1
  PENDING_WM="$(sqlite3 "$GUARD/games.db" \
    "SELECT COALESCE(MAX(started_at), '$wm') FROM games WHERE rows=12 AND cols=12;")" || return 1
}

# Advance the watermark — call ONLY after a run that actually consumed the fetched games.
guard_commit() { [ -z "${PENDING_WM:-}" ] || echo "$PENDING_WM" > "$WM_FILE"; }

# --- one MCTS self-play generation -----------------------------------------------------
run_mcts() {
  local gen rc=0
  gen="$(cat "$WORK/gen" 2> /dev/null || echo 1)"
  # The curriculum stage consumes the guardrail's fetch; if it failed, the stage skips itself.
  GAMES_DB="$GUARD/games.db" \
    nice -n "$NICE_LEVEL" "$ROOT/scripts/mcts_selfplay_gen.sh" "$WORK"
  rc=$?
  if [ "$rc" -le 1 ]; then
    # Generation completed (0 = PROMOTE, 1 = KEEP) — this window's games are consumed.
    guard_commit
  fi
  if [ "$rc" -eq 0 ]; then
    cp "$WORK/champion_gen$gen.json" "$WORK/out/"
    {
      echo "# MCTS self-play promotion — gen $gen — $(date '+%Y-%m-%d %H:%M')"
      echo
      echo "The gen-$gen candidate beat the champion at the gate and is the new champion."
      echo
      echo '```'
      tail -5 "$WORK/history.log" 2> /dev/null
      echo '```'
      echo
      echo "- $GUARD_NOTE"
      echo "- Artifact: \`out/champion_gen$gen.json\`"
      echo "- To ship: review, copy into the repo as \`mcts_policy.json\`, and commit."
      echo "  This container never commits or pushes."
    } > "$WORK/out/champion_gen$gen.md"
    echo "##############################################################"
    echo "## PROMOTION: gen $gen candidate is the new champion."
    echo "## Artifacts: $WORK/out/champion_gen$gen.json + .md"
    echo "##############################################################"
    log_run "mcts gen $gen PROMOTE — $GUARD_NOTE"
  elif [ "$rc" -eq 1 ]; then
    echo ">> gen $gen: KEEP — champion unchanged"
    log_run "mcts gen $gen KEEP — $GUARD_NOTE"
  else
    echo ">> gen $gen: stage error (rc=$rc) — stamps make the next window resume it"
    log_run "mcts gen $gen ERROR rc=$rc — $GUARD_NOTE"
  fi
}

# --- one NNUE v3 retrain ---------------------------------------------------------------
run_v3() {
  local day dir rc=0
  day="$(date +%Y%m%d)"
  dir="$WORK/v3-$day"
  # Consume exactly the DB the guardrail counted (checkpointed, so the WAL is folded in).
  GAMES_SRC="$GUARD/games.db" \
    nice -n "$NICE_LEVEL" "$ROOT/scripts/v3_retrain_loop.sh" "$dir"
  rc=$?
  if [ "$rc" -le 1 ]; then
    # Retrain reached its verdict (0 = PASS, 1 = gate FAIL) — the games were consumed.
    guard_commit
  fi
  if [ "$rc" -eq 0 ]; then
    cp "$dir/candidate_net.json" "$WORK/out/nnue_v3_candidate_$day.json"
    {
      cat "$dir/report.md" 2> /dev/null
      echo
      echo "- $GUARD_NOTE"
      echo "- Artifact: \`out/nnue_v3_candidate_$day.json\`"
      echo "- To ship: review, copy into the repo as \`nnue_v3_net.json\` (plus the parity"
      echo "  fixture, see v3_retrain_loop.sh's PASS output), and commit."
      echo "  This container never commits or pushes."
    } > "$WORK/out/nnue_v3_report_$day.md"
    echo "##############################################################"
    echo "## v3 GATE PASS: candidate in $WORK/out/nnue_v3_candidate_$day.json"
    echo "##############################################################"
    log_run "v3 $day PASS — $GUARD_NOTE"
  elif [ "$rc" -eq 1 ]; then
    echo ">> v3 $day: gate FAIL — candidate stays in $dir"
    log_run "v3 $day gate FAIL — $GUARD_NOTE"
  else
    echo ">> v3 $day: stage error (rc=$rc) — stamps make the next window resume it"
    log_run "v3 $day ERROR rc=$rc — $GUARD_NOTE"
  fi
}

# --- main loop -------------------------------------------------------------------------
echo ">> trainer sidecar up: loop=$TRAINER_LOOP window=${TRAIN_SCHEDULE_HOUR}:00 work=$WORK"
echo ">>   GAMES=$GAMES SIMS=$SIMS MIN_NEW_GAMES=$MIN_NEW_GAMES RL_REQUIRE_NEW_GAMES=$RL_REQUIRE_NEW_GAMES CURRICULUM=$CURRICULUM"

[ "${TRAIN_ON_START:-0}" = 1 ] || sleep_until_window

while :; do
  GUARD_NOTE=""
  SKIP=0
  # ENFORCE: low new-game count skips the run. FETCH_ONLY: the mcts curriculum stage wants a
  # fresh DB, but neither a failed fetch nor few new games may block the RL generation.
  ENFORCE=0
  FETCH_ONLY=0
  { [ "$TRAINER_LOOP" = v3 ] || [ "$RL_REQUIRE_NEW_GAMES" = 1 ]; } && ENFORCE=1
  [ "$TRAINER_LOOP" = mcts ] && [ "$CURRICULUM" = 1 ] && FETCH_ONLY=1
  if [ "$ENFORCE" = 1 ] || [ "$FETCH_ONLY" = 1 ]; then
    if guard_check; then
      if [ "$ENFORCE" = 1 ] && [ "$NEW_GAMES" -lt "$MIN_NEW_GAMES" ]; then
        GUARD_NOTE="guardrail: $NEW_GAMES new games since watermark (< $MIN_NEW_GAMES) — skipped"
        SKIP=1
      else
        GUARD_NOTE="guardrail: $NEW_GAMES new games since watermark — run"
      fi
    elif [ "$TRAINER_LOOP" = v3 ]; then
      GUARD_NOTE="guardrail: fetching $GAMES_URL failed — skipped (v3 needs that DB anyway)"
      SKIP=1
    else
      GUARD_NOTE="guardrail: fetching $GAMES_URL failed — running anyway (self-play needs no prod games; curriculum stage will skip or use the last fetch)"
    fi
  else
    GUARD_NOTE="guardrail: not consulted (mcts with RL_REQUIRE_NEW_GAMES=0, CURRICULUM=0)"
  fi
  echo ">> $GUARD_NOTE"

  if [ "$SKIP" = 1 ]; then
    log_run "$TRAINER_LOOP SKIPPED — $GUARD_NOTE"
  else
    case "$TRAINER_LOOP" in
      v3) run_v3 ;;
      mcts) run_mcts ;;
      *)
        echo "ERROR: unknown TRAINER_LOOP='$TRAINER_LOOP' (mcts|v3)" >&2
        exit 2
        ;;
    esac
  fi

  sleep_until_window
done
