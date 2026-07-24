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

<!-- RESULTS_TABLE -->

## Speed caveat (honest)

- v2 float prototype throughput: **~5.8k eval/s** on 12x12.
- Hand-tuned eval: dramatically faster (integer arithmetic, no matmul).
- **Fixed depth** isolates *eval quality* from speed — both sides search the
  same tree, so this is the fair strength comparison. In **real-time** (equal
  wall-clock) v2 would search much shallower than hand-tuned and do even worse.

## Verdict

<!-- VERDICT -->
