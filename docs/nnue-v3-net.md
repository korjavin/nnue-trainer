# NNUE v3.5 — the nonlinear ordering net

Bead `nnue-trainer-1uz`, training half. Artifact: `nnue_v3_net.json` at the repo root.

> **CORRECTION (frame bug).** Everything this document said before was measured in a frame the
> engine never queries. `V3SiblingDatasetEmitter` and `V3OrderingProbe` scored every child from the
> **parent mover's** perspective; `GoBotSearcher.leafEval` evaluates each leaf from the **leaf's
> own** `currentPlayer()` and negates into the root frame. 47% of children end the turn, so the
> mover flips on them and the old labels had the sign inverted. **The previously headlined 76.0%
> held-out top-1 is void.** The corrected numbers are below, and they do not support the old
> conclusion: once the frame is right, the hidden layer buys nothing over the linear model.

## The bug

`leafEval` for `LeafEval.NNUEV3`:

```java
int mover = state.currentPlayer();
long v = nnueV3Leaf(state.toBoard(), mover, nnueV3);
return mover == root ? v : -v;
```

The emitter did this instead, for every child:

```java
int mover = state.currentPlayer();                       // the PARENT's mover
int ht = HandTunedEval.staticEval(child.toBoard(), mover, child.movesLeft(), s.neutralUsed);
out.add(new Child(V3FeatureMiner.activeFeatures(child.toBoard(), mover), ht));
```

Two defects on those lines:

1. **Frame.** Any action that ends a turn — a neutral placement, or the last action of a turn —
   flips `currentPlayer()`. 105 525 of 223 893 rows (**47.1%**) are such children. They were
   featurized and labelled from the parent's mover, a frame the runtime never produces.
2. **Neutral bookkeeping.** The parent's `s.neutralUsed` was passed even for a neutral-placement
   child, which has by definition just spent its neutral.

### Why it destroyed playing strength

In the parent's frame the hand-tuned eval hates ending your turn early — you give up your remaining
actions:

| parent-frame mean `ht` | turn-keeping children | turn-flipping children |
|---|---|---|
| | **+6 958** | **−14 108** |

The best child is a turn-flipper in only **1.0%** of the 6 551 sibling groups. The buggy emitter
labelled a flipper with that parent-frame value, so the net learned `f(flipper features) ≈ −14 108`.
The runtime then negated it, yielding **+14 108** — so the engine ranked precisely the moves the
hand-tuned eval calls worst as its best. Measured with the corrected `V3OrderingProbe` (400
positions, 11 789 children), both shipped models order **worse than random**:

| model, in the frame the engine actually uses | top-1 | mean ρ | ρ ≤ 0 | top-3 overlap |
|---|---|---|---|---|
| v3.1 linear (`NNUEv3Evaluator`) | 9.8% | −0.161 | 63.3% | 29.3% |
| v3.5 net H=32, buggy-frame training | 14.5% | −0.298 | 84.0% | 14.8% |

The net's top-3 overlap (14.8%) is barely above its top-1 (14.5%): its whole top-3 is flippers.
This is a complete explanation of both gauntlet failures — v3.1's 7-17 and the net's 20.8% at
depth 3.

## The fix

`V3SiblingDatasetEmitter` now emits each child in the runtime frame and carries the sign:

```java
int cp = child.currentPlayer();
boolean[] nu = {child.neutralUsed(1), child.neutralUsed(2)};
int ht = HandTunedEval.staticEval(child.toBoard(), cp, child.movesLeft(), nu);
out.add(new Child(V3FeatureMiner.activeFeatures(child.toBoard(), cp), ht, cp == mover ? 1 : -1, ...));
```

Row schema is now
`{"game_id": G, "pos_id": P, "active": [144 ids], "ht": S, "s": ±1, "ml": M}`. `active`/`ht` are in
the **child's** frame — what the net is queried with — and `s` is `leafEval`'s negation, so the
**parent** frame is `s * value`. Everything that ranks — the ListNet loss, top-1, Spearman — is
computed on `s * value`. The MSE anchor is unaffected (squaring makes the sign irrelevant). `ml` is
the child's `movesLeft`, metadata for the tempo experiment only; the shipped 1152 features cannot
see it.

`V3OrderingProbe` got the same correction. Its old `--- resolution` block quoted a hardcoded
"v3 holdout MAE 1230" — measured on **parent positions** — against a 1299 gap measured between
**children**. That ratio was meaningless and is gone; the probe now measures `median |v3 − ht|` on
the same children it measures the gap on.

One subtlety the fix has to get right: in the ListNet loss the standardisation offset no longer
cancels. Softmax is invariant to a shift shared by the whole row, but a sibling group holds both
signs, so `μ` must be folded back in — the logits are `s·(z + μ/σ)/(T/σ)`, i.e. `s·raw/T`.

## The pipeline

```bash
./mvnw -q compile exec:java \
  -Dexec.mainClass=com.engine.nnue_trainer.train.V3SiblingDatasetEmitter \
  -Dexec.classpathScope=runtime -Dexec.args="/path/to/games.db /tmp/nnue_v3_siblings.jsonl"

python3 -m python.v3.train_net /tmp/nnue_v3_siblings.jsonl \
  --sweep 0 16 32 64 128 --epochs 40 --patience 6
```

```
446 games -> 6551 sibling groups -> 223893 rows (avg 34.2 children per group), 47.1% turn-flipping
```

The corpus is unchanged by the fix — same games, same groups, same rows. Only the labels, the
features on 47.1% of rows, and the ranking frame changed. The median best-minus-2nd sibling gap in
the parent frame is still **1299**, so `T = 1300` remains the right ListNet temperature and the
loss blend below was not re-tuned.

## Architecture and objective

Unchanged from the original design, and still correct:

```
eval(x) = w2 · relu(W1·x + b1) + b2          loss = 1.0 * ListNet(T=1300) + 10.0 * MSE
```

`x` is 0/1 over 1152 features with exactly 144 active, so `W1·x` is a sum of columns —
`nn.EmbeddingBag(mode="sum")`, never a dense matmul. Output is raw hand-tuned units; the target
standardisation is folded into the exported `w2`/`b2`. `python/v3/train_net_test.py` pins that
round-trip.

## H sweep, corrected frame

Three-way split **by game**: 268 train / 89 val / 89 test. Val early-stops (patience 6 on val
top-1) and picks H; test is the only number reported. The seed sets both the split and the init.

**Held-out TEST top-1:**

| H | params | seed 0 | seed 1 | seed 2 | **mean** | val mean |
|---|---|---|---|---|---|---|
| **0 (linear)** | 1 153 | 71.6% | 78.5% | 69.6% | **73.2%** | 71.7% |
| 16 | 18k | 68.1% | 75.3% | 68.7% | 70.7% | 69.8% |
| 32 | 37k | 68.3% | 80.2% | 69.9% | 72.8% | 71.0% |
| 64 | 74k | 69.1% | 77.5% | 69.3% | 72.0% | 70.7% |
| 128 | 148k | 69.1% | 78.4% | 71.0% | 72.8% | 72.1% |

**The hidden layer buys nothing.** The linear H = 0 row is at the *top* of the test mean; the whole
spread across H is 2.5 points against a between-seed spread of ~10 (seed 1's split is easier at
every H, exactly as before). Selection on mean val top-1 picks H = 128 by 0.4 points over H = 0 —
noise, and its test mean is 0.4 *below* H = 0.

Compare the pre-fix sweep, which reported H = 32 at 78.7% mean vs H = 0 at 74.0% and concluded the
nonlinearity was worth 4.8 points. **That gap was an artifact of the broken frame.** It is gone.

**Ships: H = 32, seed 0.** Not because it won — nothing won. The architecture is held constant
against the prior H = 32 gauntlet so the gauntlet below isolates the *frame fix* rather than
confounding it with a capacity change. Any of H = 0/32/64/128 would be an equally defensible pick
from this table, which is itself the finding.

## Result vs the baselines

| model (held-out, corrected frame) | top-1 | ρ mean |
|---|---|---|
| v3.1 linear, as shipped, measured by the fixed probe | 9.8% | −0.161 |
| v3.5 net H=32 trained in the buggy frame, fixed probe | 14.5% | −0.298 |
| linear (H = 0) retrained on corrected data, 3-seed mean | 73.2% | 0.722 |
| **v3.5 net H = 32 retrained on corrected data, 3-seed mean** | **72.8%** | 0.731 |

Both "on record" baselines from the old document — linear 41.2% and net 76.0% — were measured in
the broken frame and are not comparable to anything here. They are not the numbers to beat; they
are not numbers at all.

## Gauntlet

`V3EVAL=net MATCHUP=bar ./mvnw exec:java -Dexec.mainClass=...GauntletV3Run -Dexec.args="24 3,4 7"`

| leaf eval | depth 3 | depth 4 |
|---|---|---|
| v3.1 linear (buggy-frame training) | 29.2% | 29.2% |
| v3.5 net H=32 (buggy-frame training) | 20.8% | 41.7% |
| **v3.5 net H=32 (corrected frame)** | **41.7%** (10-14-0) | **29.2%** (7-17-0) |

Hand-tuned bar = 50%; the goal of a distillation is to reach it, not beat it.

**The frame fix did not measurably improve playing strength.** Pooled over both depths (48 games
each): corrected net 17-31 = **35.4%**, buggy-frame net 15-33 = 31.3%, linear 14-34 = 29.2%. With 24
games per cell the standard error on a difference of two ~33% win rates is about **9.6 points**, so
a 4-6 point spread is well inside noise. The two nets' per-depth numbers even swapped values
(20.8/41.7 vs 41.7/29.2), which is what noise looks like.

State it plainly: the bug was real, the offline correction is enormous — ordering went from ρ =
−0.298 (worse than random, in the engine's own frame) to ρ = +0.756 and top-1 from 14.5% to 77.5% —
and **none of that showed up as strength at this sample size**. This is now the fourth time an
offline metric has failed to predict the gauntlet on this project. Anyone claiming a strength result
here needs several hundred games per cell, not 24.

## The tempo blind spot

The 1152 features are `(row, col, cell-state)` indicators. They contain **no tempo term**, but
`HandTunedEval` adds `movesLeft × 12` for the side to move, and after the frame fix children
legitimately differ in `movesLeft`: 2 for a turn-keeping child, 3 for a fresh turn.

Experiment (`--tempo`): append a 4-way `movesLeft` one-hot, 1152 → 1156 features, 145 active.

**Held-out TEST top-1, same splits and seeds:**

| features | H | seed 0 | seed 1 | seed 2 | **mean** |
|---|---|---|---|---|---|
| 1152 (shipped) | 0 | 71.6% | 78.5% | 69.6% | **73.2%** |
| 1156 (+tempo) | 0 | 67.6% | 78.9% | 67.8% | 71.4% |
| 1152 (shipped) | 32 | 68.3% | 80.2% | 69.9% | **72.8%** |
| 1156 (+tempo) | 32 | 69.6% | 75.1% | 73.6% | 72.8% |

**No improvement.** At H = 32 the mean is identical (72.8% either way); at H = 0 the extra feature
is 1.8 points *worse*. The per-seed numbers move around by up to 6 points in both directions, which
is the split noise, not a signal. The blind spot is real — the feature set genuinely cannot see
`movesLeft` — but handing the model that information does not move held-out top-1, so tempo is not
what is limiting it. **Recommendation: do not bump the schema for this.**

**Not shipped, and it must not be shipped casually.** `NNUEv3Accumulator.FEATURES` is 1152 in the
Java runtime and `NNUEv3NetEvaluator` rejects any other width, and the parity fixture is built for
1152. Widening the schema needs a coordinated Java + fixture bump. `--tempo` also refuses `--out`
for that reason.

There is also a **caveat that limits how much this experiment proves**: `GamesDbReplay` rebuilds
every snapshot with `MOVES_LEFT = 3`, so in this corpus `movesLeft = 2` ⟺ turn-keeping and
`movesLeft = 3` ⟺ turn-flipping, exactly. The one-hot is therefore a near-perfect "did the turn
flip" indicator here, which is *not* what it would be in a real search where the parent can sit at
any `movesLeft`. Any gain it shows is an upper bound.

## What this establishes

1. **The frame bug, not model capacity, was the binding constraint.** Two models that looked like
   41.2% and 76.0% offline were in fact ordering below random in the engine's own frame.
2. **The nonlinearity was never doing the work.** With correct labels, H = 0 through H = 128 are
   indistinguishable, and the linear model tops the test mean. The +4.8-point "capacity win" in the
   previous version of this document was measurement error.
3. **Feature resolution is still the ceiling.** Absolute occupancy indicators cannot see
   connectivity, liberties, or mobility — the things `HandTunedEval` actually computes and the
   things that change when one stone is placed. That diagnosis from the original document survives;
   it is now the *only* thing that survives.

Offline ordering agreement has now failed to predict strength three times. Treat the gauntlet, not
top-1, as the result.

## Reproducing

```bash
# dataset (needs games.db; ~1 min)
./mvnw -q compile exec:java \
  -Dexec.mainClass=com.engine.nnue_trainer.train.V3SiblingDatasetEmitter \
  -Dexec.classpathScope=runtime -Dexec.args="/path/to/games.db /tmp/nnue_v3_siblings.jsonl"

# H sweep, three seeds (~12 min per seed on 4 CPU cores; the JSONL caches to a .npz)
for s in 0 1 2; do
  python3 -m python.v3.train_net /tmp/nnue_v3_siblings.jsonl --sweep 0 16 32 64 128 --seed $s
done

# export the shipped artifact + its parity fixture
python3 -m python.v3.train_net /tmp/nnue_v3_siblings.jsonl --hidden 32 --seed 0 --out nnue_v3_net.json
python3 scripts/gen_v3_net_fixture.py

# the closed-form linear ridge control, and the pure-regression control
python3 -m python.v3.train_net /tmp/nnue_v3_siblings.jsonl --ridge
python3 -m python.v3.train_net /tmp/nnue_v3_siblings.jsonl --sweep 0 32 --rank-weight 0 --mse-weight 1

# the tempo experiment (1156 features; cannot be exported)
python3 -m python.v3.train_net /tmp/nnue_v3_siblings.jsonl --sweep 0 32 --tempo

# ordering probe in the engine's frame, and the gauntlet
V3EVAL=net ./mvnw -q exec:java -Dexec.mainClass=com.engine.nnue_trainer.train.V3OrderingProbe \
  -Dexec.classpathScope=runtime -Dexec.args="/path/to/games.db 400"
V3EVAL=net MATCHUP=bar ./mvnw -q exec:java \
  -Dexec.mainClass=com.engine.nnue_trainer.train.GauntletV3Run -Dexec.args="24 3,4 7"

# tests
python3 -m unittest discover -s python/v3 -p "*_test.py"
./mvnw test
```

## 2026-08-07 retrain: 2.6x corpus, offline up, strength flat

Fresh prod games.db: 1126 usable games -> 17 394 sibling groups -> 577 664 rows (same 47% turn-flip
share). H sweep, 3 seeds, corrected frame: **the hidden layer now beats linear offline** (test top-1
means: H=0 73.7%, H=16 78.3%, H=32 78.8%, H=64 78.8%, H=128 77.9%) — the capacity that bought
nothing at 224k rows buys ~5 points at 578k. Shipped: H=32 seed 0, holdout top-1 81.6%, live-frame
probe top-1 **86.6%** (prior net: 77.5%), parity PASS.

Strength, with properly powered samples (openings disjoint per run — see the seed-overlap caveat
below):

| matchup, depth 3 unless noted | games | result |
|---|---|---|
| retrained net vs HAND_TUNED | 400 | 97 W = **24.3% ± 2.1** |
| retrained net vs HAND_TUNED, depth 4 | 208 | 29 W = **13.9% ± 2.4** |
| retrained net vs prior net (`MATCHUP=netb`) | 400 | 208 W = **52.0% ± 2.5** |

**The sixth offline-online disconnect, and the cleanest:** +9 points of live-frame top-1 ordering
produced zero measurable strength (52% head-to-head is a tie). Depth 4 being *worse* than depth 3
says search amplifies the eval's tail errors — consistent with the probe's resolution verdict
(median model error 2499 vs the 1125 sibling gap it must judge, 2.2x). Static-eval distillation on
the 1152 positional features has hit its ceiling; the levers that remain are tempo-aware features
(the `movesLeft` blind spot above) and deep-search/outcome labels (bead d4a.6.4).

**Seed-overlap caveat (bead nnue-trainer-riy):** `GauntletMatch` derives openings as
`config.seed + game/2`, so nearby seeds replay overlapping opening sets — the historical
"robust across seeds 7/11/23" runs shared 8 of 12 opening pairs. All samples in the table above use
seed ranges spaced 1000 apart (disjoint openings); `MATCHUP=netb` + `NNUEV3NET_B=<path>` plays the
env-selected v3 eval against a second net artifact.
