# NNUE v3 runtime leaf

The v3 evaluator turns `nnue_v3_weights.json` into a leaf evaluation the search can call:

```
eval_v3(board, stm) = bias + Σ_{f active} weight[f]      // exactly 144 active features
idx(r, c, state)    = (r * 12 + c) * 8 + state           // 1152 dense slots
```

- `v3/NNUEv3Accumulator` — the 144 active feature ids for a `(board, stm)` pair. Delegates `idx` and
  `activeFeatures` to `train/V3FeatureMiner` so runtime and training data cannot drift apart.
- `v3/NNUEv3Evaluator` — `load(Path)` into a flat `double[1152]` + bias, `evaluate(Board, int stm)`
  returning an STM-relative score. Full recompute per call; no incremental updates (see NPS below).

## How to enable

| what | how |
| --- | --- |
| legacy negamax search (`SearchEngine`) | `EVAL=NNUEV3` (env or `-DEVAL=NNUEV3`), with `SEARCH=NEGAMAX` |
| GoBot search leaf | programmatic: `GoBotSearcher.configureDefaultLeafEvalV3(LeafEval.NNUEV3, evaluator)` |
| weights path override | `NNUEV3_WEIGHTS=/path/to/weights.json` (default: repo-root `nnue_v3_weights.json`) |

`EVAL=NNUEV3` is read once per `SearchEngine` instance and gates the branch in `evaluate`. The
weights load lazily into a `static volatile` shared evaluator behind a double-checked lock, so a
process pays for it once; a load failure sets `v3LoadFailed`, warns once on stderr, and falls back to
the default eval instead of propagating.

**`EVAL=NNUEV3` does not reach the live bot's GoBot search.** `GameLoopHandler`'s static leaf-eval
hook only recognizes `EVAL=NNUE` (the v1 net) — same as the v2 leaf before it, which is also
programmatic-only. In production, where `SEARCH=GOBOT` is the default, setting `EVAL=NNUEV3` leaves
the GoBot leaf hand-tuned. It does not do so silently: `GameLoopHandler.unwiredEvalWarning` prints a
startup warning for any `EVAL` the GoBot leaf ignores, so a harness cannot report hand-tuned results
as v3's. The GoBot v3 leaf exists for `configureDefaultLeafEvalV3` callers: the benchmark, the tests,
`GauntletMatch.play(Object, Object, Config)` (which dispatches on the side type), and the `d4a.6.2`
gauntlet. Wire it into `GameLoopHandler` only if a gauntlet result justifies it.

Default OFF either way: with `EVAL` unset, `isUseNnueV3Eval()` is false and `newSearcher` returns
`LeafEval.HAND_TUNED`, so play is byte-identical to today's hand-tuned behavior.

## Where the weights come from

`nnue_v3_weights.json` (repo root, merged in #94) is the ridge fit produced by
`python/v3/fit_capacity.py` over positions mined by `V3FeatureMiner --emit-positions`. Its `meta`
carries the provenance the runtime validates and the report quotes:

| field | value |
| --- | --- |
| `bias` | 9912.065 |
| `lambda` | 100.0 |
| `top_n` / `n_features_total` | 1152 / 1152 (the full dense feature space) |
| `r2_holdout` / `corr_holdout` | 0.9756 / 0.9879 |
| `mae_holdout` | 1230 (labels span roughly ±30000) |
| corpus | 446 games, 6589 positions (5300 train / 1289 holdout, `split_seed` 0) |

See `docs/nnue-v3-capacity.md` for the fitting methodology and how to refit. `load` validates the
file up front rather than failing with an AIOOBE mid-search: `meta` and `weights` must be objects,
keys must be integers in `[0, n_features_total)`, `n_features_total` must be 1152 (a file fit over a
different feature space would otherwise load clean and evaluate nonsense), and every weight and the
bias must be finite. A missing index means weight 0, so a partial file loads and a `weights: {}` file
evaluates to the bias alone.

**After a refit, regenerate the parity fixture:**

```bash
python3 -m python.v3.gen_v3_eval_fixture /tmp/nnue_v3_positions.jsonl   # -> src/test/resources/v3/eval_parity_fixture.json
```

`V3EvalParityTest` (Java) and `gen_v3_eval_fixture_test.py` (Python) both check the committed fixture
against the shipped weights, so a stale fixture fails in CI rather than drifting silently.

## Score units: no scale knob, on purpose

v3 output is **already in hand-tuned eval units** — the ridge fit regressed directly on
`HandTunedEval.staticEval` values, holdout MAE 1230 on a ±30000 label range. `evaluate` returns the
raw `bias + Σ weight` sum: no scaling, no squashing, no clamping beyond the leaf's mate bounds.

Do **not** cargo-cult v2's `NNUEV2_SCALE`. That knob exists because v2 emitted a WDL-ish scalar in
`[0,1]` that had nothing to do with score units, so an entire bead (`d4a.4.1`) went into sweeping the
mapping. v3 has no such gap. If a v3 scale factor ever looks necessary, that is a signal the fit is
wrong — refit it; do not add a knob.

Perspective is STM-relative (positive = good for `stm`), matching `HandTunedEval.staticEval` and the
training labels — `V3FeatureMiner` labels each position with `staticEval(board, stm, stm, ..)`, so
the scored player and the mover coincide in the fit. Both search paths therefore query the leaf's
**own mover** (in distribution) and negate to the root's frame by zero-sum negation: the GoBot leaf
in `leafEval`, and `SearchEngine.evaluate` when `sideToMove != perspective`. Querying the root player
directly would evaluate opponent-to-move leaves a tempo out of distribution, and the fit is not
antisymmetric enough for the two to agree.

## Board size: 12x12 only, with fallback

The feature space is a dense 12x12x8 grid, so v3 is defined only on 12x12 boards.
`NNUEv3Accumulator` **rejects** other sizes rather than silently mining the top-left 12x12 — the
caller decides the fallback:

- `SearchEngine.evaluate` guards on `board.rows == board.cols == 12` and falls through to the
  baseline eval otherwise.
- `GoBotSearcher`'s v3 leaf branch does the same, falling back to the hand-tuned leaf.

The engine plays other sizes, so neither path throws. Covered by
`NNUEv3AccumulatorTest.testRejectsNon12x12`, `NNUEv3EvaluatorTest.testNon12x12Rejected`,
`GoBotNnueV3LeafTest.nonTwelveByTwelveFallsBackToHandTuned` and
`SearchEngineNnueV3Test.nonTwelveByTwelveFallsBackInsteadOfThrowing`.

## NPS benchmark

`NNUEv3BenchmarkTest` (opt-in: `NNUEV3_BENCH=1 ./mvnw test -Dtest=NNUEv3BenchmarkTest`) searches the
real corpus boards of the parity fixture at the live 60k-node budget, once with `LeafEval.HAND_TUNED`
and once with `LeafEval.NNUEV3`. Both runs expand the same 300,000 nodes (`chooseNodeBudget` stops at
exactly the limit), so the wall-clock ratio is a straight NPS comparison.

5 of the fixture's 8 boards are searched. The fixture is STM-normalized, so positions mined with
player 2 to move come back with the bases swapped; `HandTunedEval.isActive` tests the *fixed* corners
and reads both players as base-less there, so those boards terminate instantly and exercise neither
leaf. The benchmark skips them instead of letting them pad the board count with 0 nodes.

| leaf | nodes | wall | NPS |
| --- | --- | --- | --- |
| hand-tuned | 300,000 | 5,425 ms | 55,300 |
| NNUE v3 (full recompute) | 300,000 | 1,095 ms | 273,973 |

**Ratio: 5.0x faster than hand-tuned.** Single-eval throughput is ~1.36M evals/s (0.0007 ms/eval) on
a 12x12 board. Machine: AMD EPYC-Rome, Java 17.

**Is full recompute fast enough for the 60k-node budget? Yes, with a wide margin.** A full 60k-node
search costs ~219 ms with the v3 leaf versus ~1,085 ms with the hand-tuned eval. The v3 leaf is not
the bottleneck — it is cheaper than the eval it replaces, because 144 array reads and adds beat the
hand-tuned eval's per-position flood fills. Incremental accumulator updates would optimize the part
of the search that is already the fastest; they stay out of scope until a benchmark says otherwise.

## Status

Strength is unmeasured. `d4a.6.2` runs the gauntlet (v3 leaf vs GoBot and vs the hand-tuned clone).
`EVAL` stays unset in prod until that says otherwise.
