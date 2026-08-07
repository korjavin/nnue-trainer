# MCTS / AlphaZero self-play bot — feasibility study + phased design

Bead: `nnue-trainer-1jh.3`. Status: **design / feasibility — not scheduled for execution.**

## Verdict up front

**Feasible on our hardware, with a real but bounded risk.** The nets that fit this game are
tiny (12x12 board, ≤100k params), so CPU-only self-play pencils out at **hours per
generation, not weeks** — the classic "AlphaZero needs a TPU pod" objection does not apply
at this board size. The two honest risks are (a) MCTS at 1 s/move on CPU may be structurally
weaker than the clone's alpha-beta at this branching factor and tactic density, and (b) this
project has now logged **six** offline-metric/strength disconnects (`docs/nnue-v3-net.md`),
so every gate below is a real-game gauntlet at ≥400 games — offline numbers never gate
anything. Phase 1 answers risk (a) for a few days of work before any RL investment is made.

## Context — what the codebase actually says

Every number here is from the repo, not from literature.

### Game mechanics (`src/main/java/com/engine/nnue_trainer/search/gobot/GoState.java`)

- A turn is **3 actions** (`ACTIONS_PER_TURN = 3`); `movesLeft` counts 3→0.
- Two action types (`board/Action.java`):
  - `MoveAction(target: Pos)` — claim/attack one of ≤144 cells reachable from the mover's
    base-connected region (8-neighborhood). Capturing an enemy NORMAL fortifies it.
  - `PlaceNeutralsAction(pos1, pos2)` — an **unordered pair** of the mover's own NORMAL
    cells, legal only at turn start (`movesLeft == 3 && !neutralUsed`), once per game per
    player, and it **ends the turn** (`movesLeft = 0`).
- `currentPlayer` flips when `movesLeft` hits 0 or the mover goes inactive. Measured on the
  real corpus: **47.1% of legal children flip the mover** (`docs/nnue-v3-net.md`) — so
  consecutive tree plies do **not** alternate players. Any negamax-style "always negate on
  backup" is wrong on the other 53% of edges. This exact frame mistake shipped twice in v3
  and voided months of offline numbers; it is the number-one correctness hazard for MCTS
  here.
- Branching: **~34 legal actions per position on average** (577,664 rows / 17,394 sibling
  groups; `docs/nnue-v3-net.md`). Neutral pairs inflate turn-start positions (C(owned,2));
  mid-turn positions are pure move actions.
- Terminal: base destruction / no-move elimination via `eliminateStuckPlayers` +
  `finishIfTerminal`; `outcomeWinner()` adds the territory tiebreak for capped/stuck games
  (0 = genuine tie). This is the single labeling rule all self-play must use.
- `GoState.hash()` is a full FNV-1a state key (grid + current + movesLeft + active +
  neutralUsed) — reusable as an MCTS transposition key if we ever want a DAG (we start
  tree-only).
- Game length: ~**55 recorded actions/game** in games.db (9,749 moves over 177 games,
  comment in `GamesDbReplay.java`). Self-play games budgeted at 60–100 plies.

### Current best opponent (`GoBotSearcher.java`, the thing to beat)

- Alpha-beta + PVS + TT, iterative deepening, **1 s/move** production budget
  (`PRODUCTION_BUDGET_MILLIS`), 60k-node live node budget, opening book.
- Measured NPS (`docs/nnue-v3-runtime.md`, `docs/nnue-v3-net-runtime.md`):
  hand-tuned leaf **55–65k NPS**; v3-net H=32 leaf 174k NPS. Single-eval throughput:
  hand-tuned ~10–18 µs/eval, v3 linear 2.19M eval/s, v3 net cost ≈ `0.4 + 0.08·H` µs/eval
  (H=32 → 289k eval/s, H=64 → 177k eval/s). These are the anchors all throughput math
  below is built on.
- At 1 s/move the clone completes roughly depth 5–7 with the hand-tuned leaf. That is the
  bar.

### Existing NNUE v3 pipeline (reuse candidates)

- `python/v3/train_net.py`: EmbeddingBag sparse trainer, game-split holdout, export to
  JSON + parity fixture discipline (`scripts/gen_v3_net_fixture.py`, `V3NetParityTest`).
  **Reusable pattern**, not reusable weights: the shipped `nnue_v3_net.json` (H=32) is a
  distillation of the *static hand-tuned eval* and its strength is **24.3% ± 2.1** vs
  hand-tuned at depth 3 over 400 games (worse at depth 4: 13.9% ± 2.4). It is *not* a
  useful value-net starting point — its target is the wrong function (static eval, not
  outcome), and its 1152 absolute-occupancy features provably cannot see connectivity /
  mobility (`docs/nnue-v3-net.md` §"What this establishes"). What we reuse: the replay
  path (`GamesDbReplay`), the emitter/gauntlet harness, the trainer skeleton, the
  export/parity-fixture discipline, and the frame lesson.
- Data: **1,126 usable 12x12 games** in games.db (~62k recorded actions → policy samples;
  17,394 positions with ≥3 children → 577,664 sibling rows).

### The lesson this design must not repeat

v2 lost 0-24 with excellent offline metrics. v3 linear: R²=0.976, lost 7-17. v3 net:
live-frame top-1 86.6%, strength 24.3%. Six disconnects. Additionally, gauntlet seeds must
be spaced ≥1000 apart (`GauntletMatch` derives openings from `seed + game/2`; bead
`nnue-trainer-riy`), and 24-game cells have SE ≈ ±10 pts — **every strength gate below is
≥400 games, fixed protocol, disjoint seed ranges.**

## Decision — the design

### D1. Node granularity: per-action (ply), not per-turn

Per-turn macro-moves are up to ~34³ ≈ 39k children per node: the policy head cannot put
meaningful priors on a combinatorial macro space, and one sim per macro-child is pure
waste. **Per-action nodes** keep branching ~34, which is squarely in PUCT's comfort zone;
the tree is 3x deeper in plies, which MCTS absorbs (it is depth-agnostic, unlike
fixed-depth alpha-beta). Each node is a full `GoState` (grid + `movesLeft` + `neutralUsed`
+ `current`), so there is no separate afterstate concept — the game is deterministic and
every child is a real state.

**Value frame invariant (the v3 bug, solved structurally):** because 47% of edges flip the
mover and 53% do not, backup must not assume alternation. Rule:

- The value net is always queried from the **leaf's own** `currentPlayer()` (in
  distribution — same rationale as `leafEval`).
- Immediately convert to the **absolute frame**: `v_abs = (leaf.currentPlayer == 1) ? v : -v`.
  Terminals: `v_abs = +1 / -1 / 0` from `outcomeWinner()` (0 = territory tie).
- Every node accumulates `W` in the absolute frame. PUCT selection at node `n` uses
  `sign(n) · Q_abs(child) + U`, where `sign(n) = +1` if `n.currentPlayer == 1` else `-1`.
- No per-edge negation anywhere. A unit test pins this on a hand-built 3-ply position
  containing both a turn-keeping and a turn-flipping edge (the exact case the v3 emitter
  got wrong).

Policy priors are likewise computed from the node's own `currentPlayer` — net input frame
and prior frame always coincide by construction.

### D2. Net architecture (sized to CPU inference inside MCTS)

**Input** (mover-relative, fixing v3's tempo blind spot):

- 8 planes 12x12 — one-hot cell state, exactly the existing `PatternContract.getSymbol`
  8-state encoding (reuse; runtime and training cannot drift, same trick as
  `NNUEv3Accumulator`).
- 5 scalar channels, broadcast: `movesLeft` one-hot (3), own `neutralUsed`, opp
  `neutralUsed`. Total 13 input planes.

**Trunk (primary):** 4 conv layers, 3x3, 32 channels, ReLU, no residual/BN complexity at
this depth. ~31k trunk params, ~4.5M MACs/eval.

**Policy head** — defined exactly from `Action`'s structure:

- Move logits: 1x1 conv → 12x12 map = **144 logits**, one per `MoveAction(target)`.
- Neutral pairs: the raw space is C(144,2) = **10,296** unordered pairs — too wide for a
  dense head and games.db has too few neutral placements to train it densely. Factorize:
  a second 1x1-conv 12x12 map of per-cell utilities `u[i]` plus one scalar bias;
  `logit(PlaceNeutrals{i,j}) = u[i] + u[j] + b_pair`. 289 raw outputs cover all 10,440
  distinct actions. Softmax is taken **over the legal actions only** (mask from
  `legalActions()`).

**Value head:** global average pool (32) → dense 32 → 1, tanh. Output in the mover's frame.

Parameter budget ≈ **40k params, ~4.5M MACs/eval**. Java inference is hand-rolled float
arrays like `NNUEv3NetEvaluator` (a 3x3 conv on a 12x12 board is ~60 lines; no ONNX/new
dependency). Estimated **0.5–2 ms/eval single-core** — Phase 0 measures the real number;
the acceptance bar is ≥500 evals/s/core, else the trunk drops to 3x24ch or the fallback
below.

**Fallback trunk (throughput insurance):** sparse EmbeddingBag over the existing 1152
features + tempo inputs, H=256 shared trunk, same heads (policy from a 256→289 dense
layer). ~25 µs trunk (extrapolating the measured `0.4 + 0.08·H` µs law) + ~74k-MAC policy
head ≈ **60–80 µs/eval, ~13k evals/s/core** — 20x faster than conv, reuses
`train_net.py`'s whole data path. Known ceiling: absolute-occupancy inputs cannot
represent connectivity, which caps *value* quality — acceptable for early generations,
not for the endgame. The switch is an explicit Phase 3 decision, made on gauntlet results.

**Sims per move at 1 s (production play):** conv @0.5–2 ms/eval, sim ≈ net eval + path
`apply` (~2–3 µs/apply, from the 348k-NPS linear-leaf anchor) → net-dominated: **~500–2000
sims/move** single-threaded. Sparse fallback: ~10k sims/move. No leaf batching, no virtual
loss, no tree parallelism in v1 — parallelism comes from running 8 independent games
(ponytail: per-tree parallelism is the upgrade path if 1-s strength needs it).

### D3. Self-play throughput (honest math, 8-core Linux server)

Per game: 256 sims/move × 80 plies ≈ 20.5k sims.

| trunk | eval cost | sims/s/core | s/game/core | games/h (8 cores) | 3,000-game gen |
|---|---|---|---|---|---|
| conv 4x32 (pessimistic 3 ms) | 3 ms | ~330 | 62 | ~460 | 6.5 h |
| conv 4x32 (expected 1 ms) | 1 ms | ~1,000 | 20 | ~1,440 | 2.1 h |
| sparse H=256 | 0.08 ms | ~10,000 | 2 | ~14,000 | 13 min |

Training (laptop, Apple Silicon / PyTorch MPS or plain CPU): 240k positions/gen, ≤100k
params → **minutes per epoch, ≤40 min per generation**. Gating match (400 games net-vs-net
at 256 sims): ~0.5–1.5 h on 8 cores.

**Wall-clock per generation: ~3–8 h with the conv trunk** → 2–3 generations/day → a
25-generation run is **1.5–2 weeks of server compute**. Not weeks-per-generation, so the
full loop is viable — but the cheap variant below still goes first, because it can kill or
de-risk the hypothesis in days:

**Generation 0 (the cheap variant, Phase 1):** no RL, no value net. MCTS with (a)
supervised policy priors trained on games.db moves and (b) leaf value =
`tanh(HandTunedEval / S)` (S calibrated so typical mid-game evals land in ±0.8;
hand-tuned eval ≈ 10–18 µs → **~20–30k sims at 1 s/move**). This directly measures
"does PUCT search beat alpha-beta on this game at equal time with equal evaluation
knowledge" before a single self-play game is generated.

### D4. Bootstrap from games.db

- **Policy pretraining:** ~62k (state, action) pairs from 1,126 replayed games
  (`GamesDbReplay`, same snapshot discipline as `V3SiblingDatasetEmitter` — child frame,
  child `neutralUsed`). Expected top-1 vs recorded moves: 30–50% (34-way average). That is
  enough — priors only need to concentrate PUCT on plausible moves. Risks: small corpus
  (62k samples for a 289-output head; mitigated by the factored pair head and weight
  decay); distribution = current prod play, so priors imitate the bot we want to beat
  (fine for gen 0, priors wash out under RL); neutral-pair support may be tiny — measure
  it during emission, and if <500 examples accept a near-uniform pair head.
- **Value pretraining:** label every position with `outcomeWinner()` of its game (z ∈ {+1,
  −1, 0} in the mover's frame). 1,126 outcomes is genuinely weak supervision — expect a
  high-variance value net. Use it only to initialize the gen-1 net (alongside the policy),
  never as a gate. Do **not** anchor to hand-tuned static eval as an auxiliary target
  beyond gen 0: distilling that function is the ceiling we already measured at 24.3%.
- Expected value of the bootstrap: skips the coldest, most degenerate self-play
  generations (the v2 post-mortem's 0.019 distinct-game ratio is what un-bootstrapped
  self-play looks like here). Risk if skipped: weeks of compute rediscovering opening
  basics.

### D5. Evaluation ladder (real games only)

Fixed protocol for every rung: **≥400 games**, colors alternated in pairs
(`GauntletMatch` pairing), seed ranges spaced ≥1000 between runs (bead riy), opening
diversity via the existing seeded epsilon-greedy openings. SE at 400 games ≈ ±2.5 pts —
a 55% gate is ~2σ. Never report or gate a 24-game cell.

Two time controls per rung against the clone:

1. **Fixed time, 1 s/move** (the production condition; `chooseWithDeadline` on the clone
   side — `GauntletMatch.Config` needs a small deadline option, it currently drives node
   budgets/depth).
2. **Fixed compute**: clone at its live 60k-node budget vs MCTS at a sim count matched to
   the same measured single-core wall time. Guards against "wins only because the JVM was
   warm" artifacts and lets us see scaling separately from speed.

Rungs: (i) gen-vs-prev-gen gating at ≥55%/400 for net promotion; (ii) every ~3
generations, 400 games vs the hand-tuned clone at both controls (the trend line that
decides Phase 3's kill); (iii) final: ≥55% vs clone at 1 s/move over **800** games,
disjoint seeds, plus ≥50% at fixed compute.

## Phased tasks

### Phase 0 — MCTS core + throughput ground truth (~3–5 days)

Tasks:
1. `search/mcts/`: PUCT tree over `GoState` — per-action nodes, absolute-frame backup
   (D1), legal-action masking, Dirichlet root noise (α ≈ 0.3), temperature schedule
   (τ=1 first 21 plies ≈ 7 turns, then argmax), pluggable leaf `(policy, value)` provider.
2. Leaf providers: uniform-policy + hand-tuned-value (tanh-squashed); stub net provider.
3. Correctness tests: frame-invariant test on a mixed keep/flip 3-ply position; terminal
   backup incl. territory-tie = 0; mask test (neutral pair legality at movesLeft==3 only);
   determinism under fixed seed.
4. Micro-benchmarks (seconds each, existing `NNUEV3_BENCH` opt-in pattern): sims/s with
   hand-tuned leaf; Java conv-trunk forward at 4x32ch (evals/s/core).

Acceptance: tests green; measured ≥5k sims/s/core with hand-tuned leaf; conv forward
measured (decides conv vs 3x24 vs sparse trunk per D2's ≥500 evals/s/core bar).

Kill criteria: none — this phase is cheap and its artifacts (MCTS core, benchmarks) are
reusable regardless.

### Phase 1 — the cheap decisive experiment: gen-0 MCTS vs the clone (~1 week)

Tasks:
1. Policy dataset emitter from games.db (recorded action = label; child-frame discipline;
   report neutral-pair support count).
2. `python/az/train_policy.py` (fork of `train_net.py` skeleton): policy-only net, export
   JSON, parity fixture + Java test (same discipline as `V3NetParityTest`).
3. Fixed-time option in `GauntletMatch.Config` (deadline per move) + MCTS side type.
4. Gauntlets, 400 games each, both time controls: (a) MCTS + uniform priors +
   hand-tuned value; (b) MCTS + supervised priors + hand-tuned value; cpuct/sims swept
   cheaply on 100-game screens first (screens are for tuning only, never reported).

Acceptance: both 400-game results recorded in `docs/mcts-az-phase1.md` with protocol
details (seeds, control, versions).

**Kill criteria (phase gate G1): if the better of (a)/(b) scores <15% vs the clone at
1 s/move after tuning, abandon the AlphaZero hypothesis** — PUCT with the *same*
evaluation knowledge and ~25k sims cannot live with alpha-beta on this game, and a learned
value net would have to be not just better than hand-tuned but enough better to close a
>35-point gap. Between 15% and 40%: proceed, value-net upside is the explicit bet.
Above 40%: strong green light.

### Phase 2 — supervised policy+value net, one full generation end-to-end (~1–2 weeks)

Tasks:
1. Value+policy net per D2 (trunk chosen by Phase 0 benchmark), pretrained per D4;
   Java evaluator + parity fixture.
2. Self-play worker: 8 parallel independent games, 256 sims/move, noise+temperature,
   JSONL output (state features, visit-count policy target over legal actions, z from
   `outcomeWinner()` in mover frame); resign disabled in v1 (games are short).
3. Trainer `python/az/train.py`: policy CE (visit distribution) + value MSE + L2,
   sliding window of recent generations, gen-tagged artifacts.
4. Run generation 1: self-play (3,000 games) → train → gate net-vs-net 400 games at
   fixed 256 sims.
5. Measure and record actual games/h, wall-clock/gen vs D3's estimates.

Acceptance: full loop runs unattended; measured throughput ≥300 games/h (8 cores); gen-1
net beats the pretrained gen-0 net ≥55%/400 at equal sims.

Kill criteria (G2): throughput <150 games/h after profiling (loop economically dead on
this hardware — park until hardware changes); or gen-1 cannot beat gen-0 (≤50%) after one
retrain attempt with fixed data pipeline (self-play data adds nothing over supervised —
investigate diversity first, the v2 0.019 distinct-ratio failure mode, then kill if
diversity is confirmed healthy).

### Phase 3 — the RL loop (~2–4 weeks compute, mostly unattended)

Tasks:
1. Iterate generations with ≥55%/400 net-vs-net promotion gating; keep a champion store
   (reuse `ChampionStore` pattern).
2. Every 3 generations: 400-game clone gauntlet at both time controls; plot the trend.
3. One mid-loop architecture decision point (~gen 8–10): if value quality is the visible
   binding constraint (clone-gauntlet plateau while net-vs-net still improves) and the
   trunk is the sparse fallback, switch to conv; if throughput binds, the reverse.

Acceptance: monotone-ish upward clone-gauntlet trend across gens.

Kill criteria (G3): **10 consecutive generations with no improvement in the clone
gauntlet trend, or a plateau below 35% at 1 s/move after 20 generations** — write the
post-mortem and stop. (Each check is ±2.5 pts SE; "no improvement" means the fitted trend
over ≥3 checkpoints is flat or negative, not one noisy cell.)

### Phase 4 — the final gate and productionization (~1 week)

Tasks:
1. Final match: 800 games vs the clone at 1 s/move, disjoint seed ranges; 400 games at
   fixed compute; also 400 games vs the clone's opening-book-enabled config (book on, the
   production reality).
2. If passed: wire as a `GameLoopHandler` search option behind an env flag, default OFF
   (the `EVAL` discipline; master auto-deploys — default OFF is non-negotiable per
   `docs/nnue-v3-net-runtime.md`).

Acceptance / ship gate: **≥55% over 800 games at 1 s/move AND ≥50% at fixed compute.**
Below that, the bot is archived as a research artifact and the champion stays hand-tuned.

## Explicitly out of scope (v1)

Leaf-eval batching, virtual loss / tree parallelism, DAG transpositions via
`GoState.hash()`, Gumbel/sequential-halving root selection, resign thresholds, 4-player
`maxN` support (2-player only, like every v3 artifact). Each is a known upgrade with a
trigger (1-s strength shortfall, throughput shortfall) — none earns complexity before a
measurement asks for it.

## Summary of go/no-go gates

| gate | after | condition to continue |
|---|---|---|
| G1 | Phase 1 | best gen-0 MCTS ≥15% vs clone @1 s/move, 400 games |
| G2 | Phase 2 | ≥300 games/h measured; gen-1 > gen-0 at ≥55%/400 |
| G3 | Phase 3 | clone-gauntlet trend improving; no 10-gen flat stretch; >35% by gen 20 |
| Ship | Phase 4 | ≥55%/800 @1 s/move and ≥50%/400 @fixed compute |
