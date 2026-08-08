# Server-side training sidecar

A second container, `ghcr.io/korjavin/nnue-trainer-trainer`, runs the training loops on the
same host as the bot — git-ops style: it is built and pushed by `deploy.yml` on every master
push, and it **never** commits, pushes, or SSHes anywhere. Promoted candidates land in a
volume for the owner to review and ship by hand.

Pieces: `Dockerfile.trainer` (JDK 21 + CPU-only torch on Ubuntu — torch has no musl wheels,
so the bot's alpine base does not work here), `scripts/trainer_entrypoint.sh` (the loop),
the `trainer` service in `docker-compose.yml` (opt-in via compose profile).

## Enable on a server

The service carries `profiles: ["trainer"]`, so a plain `docker compose up` does NOT start
it — each host opts in.

**Portainer**: open the stack → *Environment variables* → add `COMPOSE_PROFILES=trainer` →
redeploy the stack. Remove the variable to turn the trainer off again.

**Plain compose / podman-compose**: `COMPOSE_PROFILES=trainer docker compose up -d`
(or `docker compose --profile trainer up -d`).

Knobs are stack env vars (defaults in parentheses): `TRAINER_LOOP` (`mcts`; set `v3` for
the NNUE retrain loop), `TRAIN_SCHEDULE_HOUR` (`3` — one run starts at 03:00 container
time), `TRAINER_GAMES` (`1000`), `TRAINER_SIMS` (`192`), `GAMES_URL`
(`https://vs.wandergeek.org/data/games.db`), `MIN_NEW_GAMES` (`25`),
`RL_REQUIRE_NEW_GAMES` (`0`). Anything else the underlying scripts read (`GATE_GAMES`,
`GATE_SIMS`, `EPOCHS`, `WINDOW`, `SEEDS`, `HIDDEN`, ...) can be added to the service's
`environment:` block directly.

## What a run does

One scheduled window = one invocation of the existing resumable scripts, `nice`d and inside
the container's `cpus: 4.0` / `mem_limit: 3g` budget:

- `mcts` — one generation of `scripts/mcts_selfplay_gen.sh` (self-play → human curriculum →
  train → gauntlet gate → promote) in the `trainer-work` volume. Stamps make a
  crashed/killed run resume at the next window instead of restarting.
- `v3` — one `scripts/v3_retrain_loop.sh` run in a fresh dated workdir, fed the games DB
  the guardrail just fetched.

If a run overruns the window it is left to finish; the loop then sleeps until the next
03:00. `TRAIN_ON_START=1` skips the first sleep (smoke tests).

## Fresh-games guardrail

Before each window the entrypoint downloads the prod games DB (`GAMES_URL` plus its `-wal`
sidecar — the WAL holds the newest games) and counts 12x12 games with `started_at` newer
than the watermark stored in the volume (`guard/watermark`, the max `started_at` already
consumed; chosen over a row-count high-water mark because counts regress when prod prunes
or the WAL fetch is stale, while `started_at` only moves forward — a stale WAL just
undercounts, which fails safe).

- `TRAINER_LOOP=v3`: fewer than `MIN_NEW_GAMES` new games (or a failed fetch) **skips the
  run** — retraining on an unchanged corpus is wasted CPU.
- `TRAINER_LOOP=mcts`: the check applies only with `RL_REQUIRE_NEW_GAMES=1`. Self-play
  generates its own training data, so for the RL loop the guardrail is optional CPU
  frugality, not correctness — by default the RL loop runs every night regardless.

The watermark advances only after a run actually consumed the games. Every window appends
one line to `runs.log` in the volume including the guardrail decision.

## Human-games curriculum (expert iteration on human-reached positions)

The mcts loop runs with `CURRICULUM=1` by default in the container: after self-play,
`HumanCurriculumEmitter --human-only` replays every valid 12x12 game from the guardrail's
freshly fetched `games.db` where at least one player is human (names not matching the
`NNUE Bot`/`Bot N`/`GoBot` families), runs a deep MCTS with the **current champion**
artifact on every multi-choice position, and writes rows in the exact self-play schema
(`pv` = root visit counts, `z` = the real game outcome from the `result` column, absolute
frame) to `gen<N>/curriculum.jsonl`.

Mixing: human games are few (~200) against ~1500 self-play games per generation, and
`train_selfplay.py` has no per-file weighting — it just concatenates its input files. So
the generation script passes the curriculum file `CURRICULUM_REPEAT` (default `3`) times
alongside the self-play window. Only the *current* generation's curriculum file is used:
it is re-emitted each generation, so the targets always come from the freshest champion.

Because the emitter needs the prod DB, the entrypoint fetches it every mcts window even
with `RL_REQUIRE_NEW_GAMES=0` — but a failed fetch never blocks the RL generation: the
curriculum stage just skips (or reuses the previous fetch) with a warning in
`gen<N>/logs/curriculum_*.log`. Knobs: `CURRICULUM` (`1` in the container, `0` locally),
`CURRICULUM_SIMS` (defaults to `SIMS`), `CURRICULUM_REPEAT` (`3`). Locally:
`CURRICULUM=1 GAMES_DB=/path/to/games.db scripts/mcts_selfplay_gen.sh work/mcts-rl`.

## Where candidates land, how promotion works

Inside the `trainer-work` volume (`docker volume inspect`, or
`docker exec nnue-trainer-trainer ls /work/out`):

- `out/champion_gen<N>.json` + `out/champion_gen<N>.md` — an RL candidate that beat the
  champion at the gauntlet gate (the report includes W-L-D and the guardrail line).
- `out/nnue_v3_candidate_<date>.json` + `out/nnue_v3_report_<date>.md` — a v3 net that
  passed the ship gate.
- `runs.log` / `history.log` — one line per window / per generation.

**A candidate never auto-ships.** The bot keeps playing its committed weights until the
owner (or a follow-up commit) reviews the report, copies the artifact into the repo
(`mcts_policy.json` or `nnue_v3_net.json` + parity fixture), and pushes — which then ships
it through the normal deploy pipeline.

### Promoting an RL champion to the live bot

`SEARCH=MCTS` is the deployment path: the bot then plays the PUCT searcher with the
trained policy prior instead of the GoBot alpha-beta search. Final steps, exactly:

```bash
# 1. Copy the gated champion out of the trainer volume into the repo
docker cp nnue-trainer-trainer:/work/out/champion_gen<N>.json mcts_champion.json

# 2. Commit it under the name the bot loads by default
git add mcts_champion.json && git commit -m "promote RL champion gen<N>"

# 3. Flip the bot to the MCTS search (Portainer stack env, or .env on the host)
#    SEARCH=MCTS
#    Optional: MCTS_VALUE=net (use the champion's value head),
#    MCTS_MOVE_MILLIS=1000 (per-move budget, the default), MCTS_CPUCT, MCTS_PRIOR=<path>.

# 4. Push — the deploy pipeline ships the image; the bot logs "SEARCH=MCTS: prior=..." on
#    its first move.
git push
```

`MCTS_PRIOR` unset loads `mcts_champion.json`, falling back to the committed
`mcts_policy.json`; if neither loads the bot logs a warning and keeps playing the GoBot
search (same graceful degradation as the EVAL flags). Non-12x12 or >2-player games fall
back automatically — the prior net is 12x12 1v1 only.

## Resource expectations

Reference point: a local 8-core box ran 1500 self-play games at 192 sims in ~2.5–3.5 h.
Scaled to the defaults here (1000 games, 192 sims, 4 shared cores): roughly 3.5–5 h of
self-play, plus ~1.5–2.5 h for the 4×100-game gauntlet and minutes of training — expect
**~5–7 h per generation**, comfortably inside a nightly window. Everything runs at
`nice 10` and under the 4-CPU cgroup, so the bot and other tenants keep priority. The
scripts still fan out one JVM per *visible* core (the host's 8); that oversubscribes the
4-CPU quota harmlessly, and `JAVA_TOOL_OPTIONS=-Xmx256m` (set in the image) keeps 8 JVMs
inside the 3 g memory limit.
