# NNUE v3 — gauntlet result and the ordering diagnosis

## Gauntlet (bead d4a.6.2)

v3 as GoBot leaf vs the hand-tuned bar, **fixed depth** (the v3 leaf is ~5x faster, so equal-time
would measure speed rather than eval quality).

| matchup | mode | games | W-L-D (v3) | win% |
|---|---|---|---|---|
| v3 vs HAND_TUNED | depth=3 | 24 | 7-17-0 | 29.2% |
| v3 vs HAND_TUNED | depth=4 | 24 | 7-17-0 | 29.2% |

Robust across seeds 7 / 11 / 23 (24 games each) and 999 / 424242 (8 games each); the small runs
pool to exactly 7/24 as well. The recurring 7-17 is not a harness artifact — 7 is simply the modal
outcome at p≈0.29 — verified by 8-game runs returning 2-6, 3-5, 2-6.

**Context:** v2 was 0-24 (0%). v3 at 29.2% is a large improvement, but it is NOT the ~50% a faithful
distillation implies, and it does not clear the hand-tuned bar (which is 6-0 vs GoBot).

## The contradiction

Held-out R² against the hand-tuned static eval was **0.976** (bead d4a.6.1). An eval that reproduces
its target that well should play like it. It does not.

This is v2's failure mode in milder form: v2 had val MSE 0.018 and 97% decisive direction-accuracy
and still lost 0-24. Twice now, strong offline regression metrics have failed to predict strength.

## The diagnosis (bead 78a)

R² is measured across the whole position distribution, which is dominated by wide, easy differences.
**Search does not need that.** Search needs to order SIBLING moves — children of one position,
differing by a single action. `V3OrderingProbe` measures that directly on 500 real positions from
games.db (14823 children, avg 29.6 per position):

| metric | value | meaning |
|---|---|---|
| mean Spearman ρ | 0.586 | moderate ordering agreement, far below what R²=0.976 suggests |
| median Spearman ρ | 0.660 | |
| ρ ≤ 0 | 8.6% | ~1 position in 12 is ordered no better than randomly |
| **top-1 agreement** | **41.2%** | v3 picks hand-tuned's best move only 41% of the time |
| top-3 overlap | 59.0% | hand-tuned's best move is not even in v3's top 3, 41% of the time |

**The mechanism, quantified:**

| | |
|---|---|
| median hand-tuned gap (best − 2nd-best sibling) | **1299** |
| v3 holdout MAE | **1230** |
| ratio | **0.9x** |

The model's typical error is the same size as the difference it must resolve. It is measuring a
1300-unit gap with a ±1230 ruler. Global fit is excellent; local discrimination is at the noise
floor. That is exactly why 0.976 R² produces 29% play.

## What this changes

1. **R²/MSE is the wrong acceptance metric.** It would have passed v2 too. The metric that predicts
   strength is **top-1 sibling agreement** (and Spearman ρ over siblings). `V3OrderingProbe` should
   gate any future eval before a gauntlet is worth running.
2. **The linear model is the ceiling, not the implementation.** `eval = bias + Σ weights` cannot
   represent interaction terms, and the capacity report already located the residual in a thin tail
   — "where a linear-in-single-cells model cannot follow the hand-tuned eval's interaction terms".
   Reducing MAE well below the ~1300 sibling gap needs a nonlinear model (a hidden layer, i.e. an
   actual NNUE) and/or richer features (pairwise/region).
3. **The distillation target itself is suspect for ordering.** Regressing absolute eval values
   optimizes global fit. An objective that cares about relative ordering (deep-search values, or a
   pairwise ranking loss over siblings) optimizes what search actually consumes.

The 5x speed headroom is an asset here: a hidden layer can be afforded and the leaf would still be
competitive with hand-tuned on speed.

## Running either model

Both tools take `V3EVAL` so linear-vs-net is one flag apart and otherwise identical (same corpus,
same seeds, same opponents, same metrics):

```bash
# linear fit (default) — nnue_v3_weights.json, override with NNUEV3_WEIGHTS
java -cp target/classes com.engine.nnue_trainer.train.V3OrderingProbe /home/iv/games.db 500
java -cp target/classes com.engine.nnue_trainer.train.GauntletV3Run 24 3,4 7

# hidden-layer net — nnue_v3_net.json, override with NNUEV3NET_WEIGHTS
V3EVAL=net java -cp target/classes com.engine.nnue_trainer.train.V3OrderingProbe /home/iv/games.db 500
V3EVAL=net java -cp target/classes com.engine.nnue_trainer.train.GauntletV3Run 24 3,4 7
```

Each prints which evaluator it loaded. Per the diagnosis above, run the **ordering probe first**:
if top-1 sibling agreement has not moved well past 41.2%, the gauntlet is not worth the wall clock.
See `docs/nnue-v3-net-runtime.md` for the net runtime.
