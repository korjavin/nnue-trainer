# NNUE v3 net runtime leaf

The nonlinear sibling of `docs/nnue-v3-runtime.md`. Same 1152 absolute `(row, col, cell-state)`
features, same 144 active per position, same hand-tuned score units — one hidden layer added:

```
eval_v3net(board, stm) = b2 + Σ_h w2[h] * relu( b1[h] + Σ_{i active} w1[h][i] )
idx(r, c, state)       = (r * 12 + c) * 8 + state          // 1152 dense slots, 144 active
```

**Why:** the linear v3 fit scored held-out R² = 0.976 and still lost the strength gauntlet 7-17
(29.2%). `V3OrderingProbe` located the cause (`docs/nnue-v3-gauntlet.md`): search orders *sibling*
moves, and the linear model's MAE (1230) is ~0.9x the median sibling gap (1299) — a 1300-unit gap
measured with a ±1230 ruler. `eval = bias + Σ weights` cannot represent interaction terms at any R².
A hidden layer can.

- `v3/V3Eval` — the leaf contract shared by the linear and net evaluators (`evaluate(Board, stm)`,
  hand-tuned units, 12x12). One `LeafEval.NNUEV3` branch serves both, so the units/perspective/clamp
  arithmetic exists exactly once.
- `v3/NNUEv3NetEvaluator` — `load(Path)` + `evaluate(Board, int stm)`. Reuses `NNUEv3Accumulator`
  for active-index extraction, so runtime and training features cannot drift.

## The weights file

`nnue_v3_net.json` at the repo root, emitted by the v3 net trainer:

```json
{
  "meta": {"arch":"1152-H-1","hidden":H,"activation":"relu","features":1152,
           "score_units":"hand_tuned","games_train":N,"games_holdout":N,
           "top1_holdout":F,"spearman_holdout":F,"mae_holdout":F,"r2_holdout":F},
  "w1": [[...1152 floats...], ...H rows...],
  "b1": [...H floats...],
  "w2": [...H floats...],
  "b2": F
}
```

`load` validates all of it up front — the `PatternDictionary.load` lesson: deferred validation shows
up as an AIOOBE mid-search. Rejected at load: a missing or non-object `meta`; `meta.features` not
1152 (a net fit over a different feature space would otherwise load clean and evaluate nonsense);
`meta.hidden` not a positive int; `w1` not an array of exactly `hidden` rows; any `w1` row not 1152
long; `b1`/`w2` not exactly `hidden` long; a non-numeric or non-finite value anywhere including
`b2`. Covered by `NNUEv3NetEvaluatorTest.loadRejectsMalformedFiles`.

## Sparse forward pass

Exactly 144 of the 1152 input slots are active, so there is **no dense matmul**: the hidden
accumulator is the sum of 144 *columns* of `w1`, plus `b1`. `w1` is stored column-major
(`w1[feature * hidden + h]`) so each active feature's H weights are contiguous — a row-major
`double[H][1152]` would stride H cache lines per active feature.

```java
double[] acc = b1.clone();
for (int f : activeFeatures(board, stm)) {   // 144
  int base = f * hidden;
  for (int h = 0; h < hidden; h++) acc[h] += w1[base + h];
}
double out = b2;
for (int h = 0; h < hidden; h++) if (acc[h] > 0) out += w2[h] * acc[h];
```

Cost is `144 * H` adds plus `H` multiply-adds, vs the linear leaf's 144 adds — i.e. linearly worse
in H, which is what the NPS table below is measuring. Full recompute per call; still no incremental
updates (the linear leaf did not need them either).

## Score units: no scale knob, on purpose

Output is **already in hand-tuned eval units** — the net is fitted against `HandTunedEval.staticEval`
labels. `evaluate` returns the raw forward pass: no scaling, no squashing, no clamping beyond the
leaf's `±MATE_SCORE` bounds. Do not cargo-cult v2's `NNUEV2_SCALE`; that knob existed because v2
emitted a WDL-ish `[0,1]` scalar and cost an entire bead to sweep. If a v3 scale ever looks
necessary, the fit is wrong — refit, do not add a knob.

Perspective is STM-relative, identical to the linear leaf: both search paths query the leaf's **own
mover** (in distribution, since `V3FeatureMiner` labels with `staticEval(board, stm, stm, ..)`) and
negate into the root's frame.

## How to enable

| what | how |
| --- | --- |
| legacy negamax search (`SearchEngine`) | `EVAL=NNUEV3NET` (env or `-DEVAL=NNUEV3NET`), with `SEARCH=NEGAMAX` |
| GoBot search leaf | programmatic: `GoBotSearcher.configureDefaultLeafEvalV3(LeafEval.NNUEV3, netEvaluator)` |
| gauntlet / ordering probe | `V3EVAL=net` (see `docs/nnue-v3-gauntlet.md`) |
| weights path override | `NNUEV3NET_WEIGHTS=/path/to/net.json` (default: repo-root `nnue_v3_net.json`) |

`EVAL=NNUEV3NET` is read once per `SearchEngine` instance. The weights load lazily into their own
`static volatile` shared evaluator behind a double-checked lock — separate from the linear leaf's, so
a broken net file cannot disable the linear one. A load failure warns once on stderr and falls back
to the default eval instead of propagating. Non-12x12 boards fall back the same way.

**`EVAL=NNUEV3NET` does not reach the live bot's GoBot search**, exactly as `EVAL=NNUEV3` does not.
`GameLoopHandler`'s static hook only recognizes `SEARCH=GOBOT` + `EVAL=NNUE` (the v1 net); every
other `EVAL` value gets `GameLoopHandler.unwiredEvalWarning` on startup, so a harness cannot report
hand-tuned results as the net's. The GoBot v3 leaf exists for `configureDefaultLeafEvalV3` callers:
the benchmark, the tests, and `GauntletMatch.play(Object, Object, Config)`, which dispatches any
`V3Eval` side to it. Wire it into `GameLoopHandler` only if a gauntlet result justifies it.

### Default OFF is non-negotiable

Master auto-deploys to prod. With `EVAL` unset: `isUseNnueV3NetEval()` is false, the `evaluate`
branch short-circuits before touching either v3 evaluator, and `newSearcher` returns
`LeafEval.HAND_TUNED`. Tested by `SearchEngineNnueV3NetTest.defaultPathIgnoresBothV3Evaluators`,
which asserts that an engine with *both* v3 evaluators injected returns byte-identical scores to a
bare engine, plus `envFlagSelectsTheNetAndOnlyTheNet` and `GoBotNnueV3LeafTest.defaultOffIsUnchanged`.

## Cross-language parity

`V3NetParityTest` is the load-bearing test: if Java and Python disagree, the index mapping or the
ReLU/accumulator arithmetic is wrong and every downstream number (R², ordering, gauntlet) describes
a different model than the one that was fitted.

`scripts/gen_v3_net_fixture.py` reuses the real corpus boards already committed in the linear
evaluator's fixture (`eval_parity_fixture.json` — picked to cover every `PatternContract` state,
both `stm` values, 16 entries) and re-scores them with numpy:

```bash
python3 scripts/gen_v3_net_fixture.py                # against nnue_v3_net.json
python3 scripts/gen_v3_net_fixture.py --synth 4      # against a deterministic stub net
```

The fixture records the weights file it was generated from in `meta.weights`, and the Java test
loads exactly that file — so a regenerated fixture and its weights move together, and a stale pair
fails in CI rather than drifting. **After a retrain, rerun the generator and commit both files.**

### Right now the fixture is SYNTHETIC

`nnue_v3_net.json` did not exist when this runtime was written (the trainer is a separate bead), so
the committed fixture was generated with `--synth 4`: a deterministic H=4 stub net in
`src/test/resources/v3/net_synth_weights.json` (`w1[h][i] = ((i*(h+1)) mod 97 - 48)/100`,
`b1[h] = h-1.5`, `w2[h] = 100(h+1)`, `b2 = 12.5` — no RNG, so the generator source alone reproduces
the file). It exercises exactly the arithmetic the real weights will: the same 144-column sparse
accumulate, the same index mapping, and a ReLU that actually gates some units.

**When the real `nnue_v3_net.json` lands**, run `python3 scripts/gen_v3_net_fixture.py` (no
`--synth`), commit the regenerated `net_parity_fixture.json` — it will then name `nnue_v3_net.json`
in `meta.weights` — and delete `net_synth_weights.json` along with
`NNUEv3NetEvaluatorTest.committedFixtureWeightsLoad`. Nothing else changes.

## NPS benchmark

`NNUEv3BenchmarkTest` (opt-in: `NNUEV3_BENCH=1 ./mvnw test -Dtest=NNUEv3BenchmarkTest`) searches the
real corpus boards of the parity fixture at the live 60k-node budget with each leaf in turn. All
runs expand the same node count (`chooseNodeBudget` stops at exactly the limit), so the wall-clock
ratio is a straight NPS comparison.

Machine: this dev box, Java 21, 5 searchable fixture boards, 300,000 nodes per run. **The net is
synthetic (see above), so only the *shape* of the H scaling is real — the absolute numbers move with
whatever H the trainer picks.** One sweep, one JVM session each, hand-tuned baseline re-measured
every run (64–67k NPS, i.e. stable):

| leaf | single-eval throughput | search NPS | vs hand-tuned | 60k-node move |
| --- | --- | --- | --- | --- |
| hand-tuned | — | 65,260 | 1.00x | 920 ms |
| v3 linear | 2,187,000 eval/s | 348,837 | 5.35x | 172 ms |
| v3 net H=4 | 828,000 eval/s | 264,784 | 4.06x | 227 ms |
| v3 net H=16 | 471,000 eval/s | 223,048 | 3.46x | 269 ms |
| v3 net H=32 | 289,000 eval/s | 174,520 | 2.66x | 344 ms |
| v3 net H=64 | 177,000 eval/s | 125,576 | 1.87x | 478 ms |

**Is the net still competitive? Yes, comfortably, up to at least H=64.** Even the widest net
measured searches 1.87x faster than the hand-tuned eval it would replace, and a full 60k-node move
costs 478 ms against hand-tuned's 920 ms. The 5x headroom the linear leaf bought is real budget: it
pays for a hidden layer and still leaves the leaf cheaper than the eval it is distilling.

Two things worth noting:

- **Search NPS degrades far more slowly than eval throughput.** From H=4 to H=64 the leaf itself
  gets 4.7x slower per call but search only loses 2.1x, because move generation, `apply`, and the TT
  are a large share of a node. The leaf is not the bottleneck at any H measured.
- **Cost is linear in H, as the `144 * H` add count predicts** (0.0012 / 0.0021 / 0.0035 / 0.0056
  ms per eval for H = 4 / 16 / 32 / 64 — roughly `0.4 + 0.08 * H` µs). If the trainer wants H=128,
  extrapolate ~0.011 ms/eval and ~1.3x vs hand-tuned; that is the point where re-benching before
  shipping becomes worthwhile, and where incremental accumulator updates would finally earn their
  complexity.

Rerun with `NNUEV3_BENCH=1 ./mvnw test -Dtest=NNUEv3BenchmarkTest -Djacoco.skip=true`
(`NNUEV3NET_WEIGHTS` selects the net; falls back to the committed synthetic one).

## Status

Runtime and wiring are done and tested (238 tests green); **strength is unmeasured and the real
weights do not exist yet**. When `nnue_v3_net.json` lands:

1. Regenerate the parity fixture (above) and confirm `V3NetParityTest` passes against the real net —
   if it does not, nothing downstream means anything.
2. `V3EVAL=net java -cp target/classes com.engine.nnue_trainer.train.V3OrderingProbe games.db 500` —
   the gate is **top-1 sibling agreement**, not R². The linear fit scored 41.2%; per
   `docs/nnue-v3-gauntlet.md`, a net that has not moved that number is not worth a gauntlet.
3. Only then `V3EVAL=net ... GauntletV3Run 24 3,4 7`, at fixed depth so the comparison isolates eval
   quality from the leaf's speed.

`EVAL` stays unset in prod until step 3 says otherwise.
