# NNUE v3.6 — deep-search labels: the ladder, and the wall

Bead `d4a.6.4`, run overnight 2026-08-07/08. The premise, from the end of
[nnue-v3-net.md](nnue-v3-net.md): static-eval distillation on the 1152 positional features hit its
ceiling — six offline-online disconnects, 24.3% at depth 3 with a fully retrained net, *worse* at
depth 4. One of the two remaining levers was deep-search labels: label each training child with a
fixed-depth search value instead of the static eval, so the runtime search queries a strictly
deeper oracle.

The lever works — and then it breaks, in an instructive way. Both halves are the result.

## The corpus and the emitter

`V3DeepLabelEmitter` (same positions, sibling groups, features, and JSONL schema as
`V3SiblingDatasetEmitter`, including the frame fix and the `s` sign) labels each child with a
fixed-depth `GoBotSearcher` negamax value from the **child's own** `currentPlayer()` — the frame
`leafEval` queries. Mate-scale scores are certainty, not eval units, so labels are clamped to
±50 000. The value lands in the `"ht"` key, so `python.v3.train_net` consumes it unchanged.

```
fresh prod games.db: 1126 usable games -> 575 238 sibling rows
```

Slightly fewer rows than the static emitter's 577 664 from the same db: children where the
depth-D search cannot run are dropped.

Labelling cost is the whole game here. Depth 2 runs at **5.5 ms/row**; depth 3 at **~48–220
ms/row** on a machine under load. At half a million rows that is hours-to-days of CPU, so the
emitter shards by game index (`[shardIdx] [shardCount]`) and the runs below used 8 shards,
concatenated (sorted by `pos_id` before training).

## Offline: each net trained against its own oracle

| labels | net | held-out top-1 vs its own oracle |
|---|---|---|
| depth 2 | H = 64 | 82.9% |
| depth 3 | H = 64 | 71.7% |

Note the direction: as the labels deepen, the offline number *falls* — a depth-3 oracle is harder
to imitate than a depth-2 one — while (below) strength *rises*. Offline agreement and strength
move in opposite directions across this ladder. That is the seventh time on this project that an
offline metric fails to predict the gauntlet, and the most instructive: the two numbers are not
even monotone in the same direction.

## The label-depth ladder

Gauntlet vs HAND_TUNED at **fixed depth 3**, 400 games per rung, hashed/spaced opening seeds
(disjoint openings per the seed-overlap caveat, bead `nnue-trainer-riy`):

| training labels | gauntlet vs hand-tuned, depth 3 |
|---|---|
| static eval (the retrained v3.5 net) | 24.3% ± 2.1 |
| depth-2 search values | 28.5% ± 2.3 |
| **depth-3 search values** | **44.3% ± 2.5** |

The ladder is monotone and each step is outside the error bars. Twenty points from static to
depth-3 labels, at identical architecture, features, corpus, and runtime search. **The label
hypothesis is vindicated at its sweet spot**: baking plies of search into the label is worth real
strength, and it is the first lever on this project that moved the gauntlet at all. 44.3% is the
closest any v3 model has come to the hand-tuned bar (50% = parity).

## The wall: the same net off its training depth

The headline of the night is not the 44.3% — it is what happens when the search around the
depth-3-label net changes. Same net, same opponent, same opening discipline:

| condition | games | result |
|---|---|---|
| fixed depth 3 (= label depth) | 400 | **44.3% ± 2.5** |
| fixed depth 4 | 200 | **9.0% ± 2.0** |
| equal time, 250 ms/move | 200 | **27.0% ± 3.1** |

One extra ply of runtime search takes the best v3 result ever measured to the worst. Under the
production-honest condition — wall clock, not depth — it lands at 27%, no better than the depth-2
rung.

State it plainly: **the 44.3% exists only at the training distribution's search depth.** The
depth-3 labels teach the net what depth-3 search needs to know about the positions depth-3 search
visits. Deeper search steers into off-distribution positions where the net's errors are large, and
then trusts the net exactly there. This is not unique to the deep-label net — it is the same
amplification every net showed tonight:

| net | depth 3 | depth 4 |
|---|---|---|
| retrained static-label net | 24.3% ± 2.1 | 13.9% ± 2.4 |
| tempo net (below) | 26.8% ± 2.2 | worse at depth 4 |
| **depth-3-label net** | **44.3% ± 2.5** | **9.0% ± 2.0** |

Deeper search makes every one of these nets *weaker*. The hand-tuned eval does not have this
property; the nets do. Distribution shift under search is the wall.

## The tempo control

The tempo experiment from [nnue-v3-net.md](nnue-v3-net.md) also concluded tonight, and it is the
clean control for "fix the offline diseases, gain nothing." The 1156-feature tempo net (runtime
now loads 1156-wide artifacts) fixed both measured pathologies of the shipped net:

| offline disease | shipped net | tempo net |
|---|---|---|
| model error vs the sibling gap it must judge | 2.2× | **1.0×** |
| positions ordered at ρ ≤ 0 | 10% | **0%** |

Strength: **26.8% ± 2.2** at depth 3 — statistically indistinguishable from the 24.3% static
baseline, and worse at depth 4 like everything else. Every offline number the project knows how
to improve was improved, and the gauntlet did not move. Meanwhile the corpus itself carries known
biases — see the which-corner seat bias caught in the
[weight heatmaps](nnue-v3-heatmap.html) — that no amount of offline polishing addresses.

## What this establishes

1. **Labels were the binding constraint, not capacity, not features, not tempo.** Depth of the
   label oracle is the first knob that produced a monotone, out-of-noise strength ladder.
2. **The gain is trapped at the training depth.** The net learns the label distribution's
   positions, and search — the thing the net exists to serve — is precisely the mechanism that
   leaves that distribution. Training deeper labels for deeper search is a treadmill: each rung
   costs ~an order of magnitude more label CPU (5.5 → ~48–220 ms/row for one ply) and defends only
   its own depth.
3. **The fix must train on search-visited positions**, not replayed-game positions relabelled
   ever deeper. That is self-play RL: generate positions with the search that will be used at
   runtime, label them with outcomes, repeat. This failure mode is the motivation, not an
   afterthought.

Self-play is implemented (MCTS Phase 2: self-play generator, joint policy+value trainer with Java
parity, resumable per-generation driver) and is awaiting its first generation. The G1 screen
against the production clone at 1 s/move, 104 games per arm: uniform prior **18.3% ± 3.8**,
trained supervised prior **24.0% ± 4.2** — both past the ≥15% feasibility gate. Those are
baseline-competence numbers for generation 0, not a strength claim.

## Depth-4 labels: the ladder breaks (with confounds)

Quarter corpus only (284 games / 148,521 rows — full-corpus depth-4 labeling costs ~20 CPU-hours).
Net H=64, offline top-1 65.3% vs its depth-4 oracle (the offline gate's first FAIL, consistent
with less data + a harder target).

| labels | gauntlet at fixed depth 3 |
|---|---|
| static eval | 24.3% ± 2.1 |
| depth 2 | 28.5% ± 2.3 |
| depth 3 | **44.3% ± 2.5** |
| depth 4 (quarter corpus) | **21.5% ± 2.1** |

Two confounds prevent a clean read: 4x less training data, and the suggestive pattern that the
ladder's peak sits exactly where **label depth equals query depth**. Distinguishing them needs the
full-corpus depth-4 run; deferred — by the time this rung completed, the self-play RL loop had
promoted its first generation (candidate 65.75% over the gen-0 champion, 400 games), making it the
primary neural line. The deep-label result stands as the strongest evidence in this project that
labels, not features, were the NNUE leaf's binding constraint — and that fixed-depth labels cannot
escape the train/query distribution coupling that self-play training dissolves by construction.

## Reproducing

```bash
# deep-label dataset, depth 3, 8 shards (hours under load; depth 2 is ~5.5 ms/row)
for i in 0 1 2 3 4 5 6 7; do
  ./mvnw -q compile exec:java \
    -Dexec.mainClass=com.engine.nnue_trainer.train.V3DeepLabelEmitter \
    -Dexec.classpathScope=runtime \
    -Dexec.args="/path/to/games.db /tmp/nnue_v3_deep_d3.$i.jsonl 3 $i 8" &
done; wait
# concatenate + sort by pos_id (the retrain-loop does this; sharded order is not group order)

# train (the deep value rides in the "ht" key; train_net is unchanged)
python3 -m python.v3.train_net /tmp/nnue_v3_deep_d3.jsonl --hidden 64 --seed 0 --out nnue_v3_net.json

# the ladder rung: 400 games, fixed depth 3, spaced seeds
V3EVAL=net MATCHUP=bar ./mvnw -q exec:java \
  -Dexec.mainClass=com.engine.nnue_trainer.train.GauntletV3Run -Dexec.args="400 3 7"

# the wall: depth 4, and wall-clock 250 ms/move (bead 1jh.1 time mode)
V3EVAL=net MATCHUP=bar ./mvnw -q exec:java \
  -Dexec.mainClass=com.engine.nnue_trainer.train.GauntletV3Run -Dexec.args="200 4 1007"
V3EVAL=net MATCHUP=bar ./mvnw -q exec:java \
  -Dexec.mainClass=com.engine.nnue_trainer.train.GauntletV3Run -Dexec.args="200 3 2007 time 250"
```
