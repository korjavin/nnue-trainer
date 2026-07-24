# NNUE v2 gauntlet — does the v2 eval beat the other bots?

bead **nnue-trainer-d4a.1.4**. The v2 pattern evaluator (`NNUEv2Evaluator`,
8135-pattern dict + float weights) is wired as a selectable **GoBot leaf eval**
(`GoBotSearcher.LeafEval.NNUEV2`) and run net-vs-net through `GauntletMatch`:
same GoBot search on both sides, differing only in the leaf eval, N games
alternating colors, fixed depth, seeded-diverse openings.

## Leaf mapping

The v2 net emits a side-to-move WDL-ish scalar (higher = better for the mover;
sanity: a clearly-winning board scores 0.3447 > a clearly-losing 0.2324). Mapped
to GoBot's `long` leaf scale as `round((wdl - 0.5) * 4000)`, clamped strictly
inside `±MATE_SCORE`. For an opponent-to-move leaf the value is negated to root
perspective — algebraically identical to the correct probability reflection
`(1 - wdl)`, so the two perspectives are exact negatives (no tempo bias).
Structurally identical to the proven v1 NNUE leaf path.

## Results (from v2's perspective, seed 7, 12x12)

| matchup | depth | games | W-L-D (v2) | win% (v2) | wall |
|---|---|---|---|---|---|
| v2 vs HAND_TUNED | 3 | 24 | 0-24-0 | **0.0%** | 249s |
| v2 vs v1-NNUE | 3 | 24 | 3-21-0 | **12.5%** | 2756s |
| v2 vs HAND_TUNED | 4 | 12 | 0-12-0 | **0.0%** | 3068s |
| v2 vs v1-NNUE | 4 | 8 | not completed¹ | | |

¹ Killed after ~60 min without finishing 8 games — depth-4 games between two
learned nets run very long at ~5.8k eval/s. Redundant anyway: depth 3 already
shows v2 losing 3-21 to v1, and the depth-4 bar confirms the depth-3 trend.

Win% = (wins + 0.5·draws) / games, from v2's perspective. Seed 7.

**Depth 4 uses reduced game counts** (12 / 8 vs 24): fixed-depth-4 search with
the ~5.8k eval/s v2 leaf is ~15x slower per game than depth 3, so 24×2 games at
depth 4 would run many hours. Depth 3 (the primary fair comparison) keeps the
full 24 games per matchup.

## Speed caveat (honest)

- v2 float prototype throughput: **~5.8k eval/s** on 12x12.
- Hand-tuned eval: dramatically faster (integer arithmetic, no matmul).
- **Fixed depth** isolates *eval quality* from speed — both sides search the
  same tree, so this is the fair strength comparison. In **real-time** (equal
  wall-clock) v2 would search much shallower than hand-tuned and do even worse.

## Verdict

**v2 does NOT beat the other bots.** As a fixed-depth GoBot leaf it loses every
game to the hand-tuned bar (0% at depth 3 and 4) and loses 3-21 (12.5%) to its
own v1 predecessor. Despite reasonable offline WDL metrics (val MSE 0.061,
dir-acc 0.918), the v2 pattern eval is a weaker in-search leaf than both
hand-tuned and v1. The mapping is verified zero-sum consistent and orientation
is confirmed by the sanity check, so this is a genuine strength result, not a
sign bug.

Default behavior is unchanged: HAND_TUNED and v1-NNUE leaf paths are untouched;
NNUEV2 is opt-in only.
