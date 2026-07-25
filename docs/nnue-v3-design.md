# NNUE v3 — 12×12 whole-board, position-aware, eval-distilled

**Status:** design / baseline (not yet implemented). Owner pivot 2026-07-25.

## Why v3 (what killed v2)

v2 (sparse counted **5×5 local pattern** features, frequency-promoted dictionary) was built end-to-end — mine → train → integrate → gauntlet — and **lost the fixed-depth gauntlet 0-24 to the hand-tuned bot** (and 3-21 to v1). We eliminated causes methodically:

- **Score scale** (1k–40k sweep): ruled out — not magnitude.
- **Training target** (raw-WDL → TD-leaf deep-search): ruled out — offline metrics were *excellent* (val MSE 0.018, 97% decisive dir-acc) yet still 0-24.
- **Root cause, confirmed by the games.db feature visualization:** the **feature-selection mechanic is broken**. Frequency-mined 5×5 windows select the most *common* local shapes — which are **trivial** (a lone stone in a corner, near-empty windows). Common ≠ informative. An eval built from non-discriminating features is ~flat across sibling moves, so search has no gradient to order moves → loses to any real eval regardless of training target.

Two independent pieces of evidence point the same way:
1. The viz: top-frequency 5×5 features are noise.
2. **v1 (whole-board, position-aware) beat v2 (5×5-local) 21-3.** Locality discarded exactly the structure this game runs on — bases, connectivity, territory are **board-scale**, and **absolute position matters**.

## The three v3 changes

1. **Fix 12×12** as the only engine board size. Drop v2's board-size independence — it was never the strength blocker, and the production bot is 12×12. (Simplifies everything.)
2. **Abandon 5×5 windows → whole-board, position-aware features.** A feature is an **absolute `(row, col, cell-state)`**, STM-perspective-normalized (self/opp). ~144 cells × ~8 states ≈ ~1.15k candidate features.
3. **Select/label features by the ported hand-tuned (GoBot) STATIC eval** (depth 0, this position — no search needed for the baseline), **not by frequency.**

## Feature-selection rule (the fix)

Rank candidate features by **eval-discrimination**, NOT frequency:

- For each feature f, over replayed positions: `discrimination(f) = |mean_eval(positions where f active) − baseline_mean_eval|`, gated by a **minimum-support floor** (enough occurrences to estimate reliably).
- Keep the top-N (~500) by discrimination.
- Frequency is only a support floor — never the ranking key. (Ranking by frequency is the exact v2 bug: it selects trivial common features.)

## Baseline model — distill the hand-tuned static eval

- `eval_v3(board) = bias + Σ_{f active} weight_f`.
- Fit `weight_f` by regressing the **hand-tuned static eval** onto the top-N feature indicator vector (ridge/linear regression) over replayed positions.
- **Capacity test (the decisive number):** report R²/correlation of `eval_v3` vs the hand-tuned static eval on held-out positions.
  - **High R²** → position-aware features *can* reproduce hand-tuned → representation foundation proven; proceed.
  - **Low R²** → single-cell granularity is too coarse; add pairwise/region features before anything else.

## Honest ceiling & the path to *beat*

Distilling the **static** hand-tuned eval can at best **match** hand-tuned, not beat it. That is the correct first target:

1. **Baseline: match.** Prove the features can reproduce hand-tuned (capacity) and tie it in the gauntlet (expect ≈50%). This alone is a massive step up from v2's 0%.
2. **Then: exceed.** Keep the same feature foundation, **swap the labels** static-eval → **deep-search value / self-play game outcome**. That's the lever that lets a fast leaf carry deep-search judgment and out-play a shallow hand-tuned bot (the classic NNUE win).

Single-cell features may prove too coarse to *beat* even with better labels → then add **pairwise / small position-anchored motifs** (still eval-discrimination-selected, still inspectable).

## Data source

- **games.db** (222 replayable real games, 12×12) — replay through the engine (reuse the `GamesDbPatternMiner` replay path from bead d4a.5.1) → 12×12 positions → hand-tuned static eval per position → absolute feature stats. Supplement with self-play if support is thin.

## Work breakdown (drive later)

- **v3.1 — feature mining + eval-regression + capacity test.** Absolute `(r,c,state)` feature stats from replayed games, discrimination-rank + support floor, top-N, ridge-fit weights vs hand-tuned static eval, report **R² (capacity)**. Decisive go/no-go.
- **v3.2 — wire v3 eval as a GoBot leaf + gauntlet vs hand-tuned.** Expect ≈tie if distillation is good. Compare to v2's 0-24.
- **v3.3 — v3 feature viz.** Render the top-N position-aware features on a 12×12 board heatmap with weights, so the owner can eyeball (this time they should look meaningful, unlike the v2 5×5 features).
- **(later) v3.4 — exceed:** re-fit weights on deep-search / outcome labels once the baseline is proven.

## Archive

Keep all `nnue-v2-*` branches (pipeline, corpus, gauntlet, etc.) — the infrastructure (games.db replay, gauntlet harness, self-play generator, eval integration) is reused. Don't delete. See memory `nnue-v2-strength-verdict` for the full v2 post-mortem.
