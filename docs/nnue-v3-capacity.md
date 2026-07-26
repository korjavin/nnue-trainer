# NNUE v3.1 — capacity report

Bead `nnue-trainer-d4a.6.1`. Work in progress: this file is written task by task as the plan
`docs/plans/20260726-v3-capacity.md` is executed.

## Task 0 — re-mined gate artifacts on the fresh 502-game corpus

The gate artifacts the owner approved were mined from a 273-game `games.db`. The owner pulled a
fresh prod dump on 2026-07-26 (502 games), so `nnue_v3_feature_stats.json` and
`docs/nnue-v3-feature-preview.html` were regenerated from it. Command:

```
./mvnw -q exec:java -Dexec.mainClass=com.engine.nnue_trainer.train.V3FeatureMiner \
  -Dexec.classpathScope=runtime          # defaults: /home/iv/games.db -> nnue_v3_feature_stats.json
```

The preview page inlines the stats verbatim between `<!-- DATA:BEGIN -->` / `<!-- DATA:END -->`;
regenerating it is a splice of the new JSON into that block, nothing else changes (headline numbers
on the page are computed from `DATA.meta` at render time).

### Recorded numbers (Task 2 and Task 3 depend on these)

| | gate run (273-game db) | this run (502-game db) |
|---|---|---|
| games_total | 273 | 502 |
| games_used | 217 | **446** |
| games_skipped | 56 | 56 |
| positions | 3385 | **6589** |
| baseline_mean_eval | -4603.23 | **-4255.25** |
| support_floor (1% of positions, min 30) | 33 | **65** |
| features observed (`meta.feature_count`) | 670 | **670** |
| features above floor | 416 | **332** |

Skip reasons are unchanged run to run (`no_pgn=7, replay_multiplayer=7, termination_disconnect=22,
termination_illegal_move=7, wrong_board_size=13`) — every skip is one of the 56 older games; all 229
new games replayed cleanly, so the rules port did not regress on fresh prod data.

Two corrections to the plan's Context section, which was written before this run:

- 446 games are usable, not 456.
- 670 is the count of *observed* features, not the count above the floor. Above the floor it was 416
  at the gate and is 332 now.

The floor is derived from the position count, so doubling the corpus doubled the floor (33 → 65)
and *shrank* the ranked set (416 → 332). That is the intended behaviour — the floor exists to keep
mean_eval estimates off tiny samples — but it means "above floor" counts are not comparable across
corpus sizes.

Consequence for Task 3: **332 features clear the floor, fewer than the design's proposed top-N ≈
500.** The top-N cut therefore has to clamp to the ranked set, and at N ≥ 332 the "top-N" fit is the
same as fitting every above-floor feature.

Data ratio for Task 4: 6589 positions against 1152 columns ≈ 5.7:1, or ≈ 19.8:1 restricted to the
332 above-floor features. Better than the gate run's ~2.9:1, still modest.

### Did the approved ranking survive the corpus doubling?

Mostly, with one caveat that is about the support floor rather than about the features.

- **Top-25 overlap: 16/25.** Top-10: 6/10. Top-50: 37/50. Top-100: 75/100.
- **Spearman ρ = 0.962** over the 332 features ranked in both runs. The ordering is stable; what
  churns is membership near the cut.
- Of the 9 features that left the top 25, **6 were demoted by the raised support floor, not by their
  discrimination**: (10,9,FORTIFIED_SELF) 59, (10,11,FORTIFIED_SELF) 47, (11,7,NORMAL_OPPONENT) 40,
  (11,8,NORMAL_OPPONENT) 45, (8,11,NORMAL_OPPONENT) 45, (10,11,FORTIFIED_OPPONENT) 49 — all under
  the new floor of 65. Their support barely moved (five of the six are *identical* old vs new): the
  229 new games contain almost no positions with those bottom-right cells occupied. The other 3 —
  (9,9,FORTIFIED_SELF), (11,10,FORTIFIED_OPPONENT), (11,9,NORMAL_SELF) — merely slipped to ranks
  28, 25 and 27.
- All 9 new entrants to the top 25 came from old ranks 26–77 — features that were already ranked and
  moved up, not newcomers. The largest jump is (1,2,NORMAL_OPPONENT), 77 → 12.
- The top of the list is unchanged in substance: (3,2,FORTIFIED_SELF) and (2,1,FORTIFIED_SELF) just
  swapped #1/#2, and (3,0,NORMAL_OPPONENT) held #3-ish.

**Finding, stated plainly:** the *design* the owner approved survives — the same kind of feature
(own fortified stones and opponent stones in the top-left approach rows) dominates, and the rank
correlation is 0.96. But the raised floor systematically drops the bottom-right-region features, and
the reason is a corpus-composition shift: the new games rarely reach positions with occupied cells
in rows 10–11. Nonempty-cell support in rows 10–11 grew by ~58% and ~67% while the position count
grew by 95%, so the ranking near the floor is sensitive to which games happen to be in the dump.
Any conclusion drawn from a single feature's rank is fragile; conclusions drawn from the ranking's
broad shape are not.

## Task 2 — why `baseline_mean_eval` is strongly negative

`baseline_mean_eval = -4255.25` (was -4603.23 at the gate). Discrimination is
`|mean_eval_f - baseline|`, so a skew here is worth understanding before any of it is read as a
feature property. Regenerate with:

```
./mvnw -q compile exec:java -Dexec.mainClass=com.engine.nnue_trainer.train.V3EvalBaselineProbe \
  -Dexec.classpathScope=runtime          # default db: /home/iv/games.db, read-only, writes nothing
```

### Distribution, not just the mean

| slice | n | mean | median | p10 | p90 | min | max |
|---|---|---|---|---|---|---|---|
| all positions | 6589 | **-4255.25** | -4847 | -28431 | 29682 | -31317 | 42424 |
| ply 0–9 | 4396 | -5736.70 | -4847 | -22984 | 5384 | -31090 | 30535 |
| ply 10–19 | 2023 | -1379.98 | -7116 | -29053 | 31641 | -31317 | 35968 |
| ply 20–29 | 110 | -583.56 | -9835 | -26488 | 31536 | -29742 | 36210 |
| ply 30–39 | 46 | +429.83 | -5225 | -24755 | 28475 | -30748 | 42424 |
| ply 40–49 | 14 | +1203.29 | -802 | -17216 | 17555 | -18439 | 22876 |
| stm = p1 | 3356 | -5012.26 | +36 | -28799 | 8445 | -30561 | 42424 |
| stm = p2 | 3233 | -3469.43 | -10064 | -23602 | 29953 | -31317 | 36210 |

46.0% of positions score positive, so the label is not one-sided — it is wide (±30k) and its
*mean* sits below zero. The mean is the wrong summary for this distribution and the report quotes
the quantiles from here on.

### The three candidate causes, checked

1. **Sign convention — ruled out.** Two active players make the utility `raw(p) − raw(opponent)`,
   so it must be exactly antisymmetric in the scored player at a fixed tempo frame. Measured over
   all 6589 positions: **0 violations** of `eval(stm) == −eval(other)`. At ply 0 (the symmetric
   start board, all 446 games) the eval is **+36 for whoever is to move** — the mover's
   `movesLeft × W_MOVES_LEFT_TEMPO` bonus, and the only asymmetry on an empty board. Both
   properties are now pinned by `HandTunedEvalSignConventionTest`.
2. **`movesLeft = 3` — not the cause, and it is the *least* negative choice.** Mean eval under the
   other assumptions: `movesLeft=0 → -5578.50`, `1 → -5423.84`, `2 → -4839.54`, `3 → -4255.25`.
   The fixed fresh-turn assumption moves the baseline by ~1300 and in the direction that *reduces*
   the negativity; it cannot produce it.
3. **A term that penalizes the side to move — there is none.** Scoring the same player but handing
   the tempo frame to the opponent gives mean `-5471.84` against `-4255.25`. The entire mass of
   mover-keyed terms (the tempo bonus and the `threatTempo` multiplier on the threat penalties) is
   therefore **+1216.59 in the mover's favour**. Every eval term keyed to the side to move helps it.

### What it actually is: one turn of tempo

A v3 position is *the board before a turn*, scored from that turn's player — so every sampled
position is scored from the side that has **not yet played**, immediately after the opponent spent
up to three actions. Measured:

- The mover holds **7.77 stones on average against the opponent's 9.39** (a 1.62-stone deficit); in
  55.4% of positions it has strictly fewer.
- The same player's score before vs after taking its turn (`−(eval[p] + eval[p+1])`) swings by
  **+11084.84 on average**, over 6143 turn pairs. Half of one turn is ≈ 5542, and the observed
  baseline is -4255 — i.e. the baseline is, to within game-length effects, exactly *minus half the
  value of a turn*.

**Finding, stated plainly:** the negative baseline is real and structural, and it is a property of
the position set, not of the eval. It is the turn-parity artifact of the snapshot definition. There
is no bug to fix, and shifting the labels would not be a fix either — the same skew would be present
in any dataset built this way, including the one the runtime leaf will see.

### What it implies for the discrimination ranking

- **A constant offset does not move the ranking at all.** Discrimination is
  `|mean_eval_f − baseline|`, and a constant `c` added to every position's eval shifts `mean_eval_f`
  and `baseline` by the same `c`. The ranking the owner approved is invariant to the size of the
  skew.
- **The residual ply-dependence is the part that does leak.** The skew is not constant: it runs
  -5737 in the opening and turns positive by ply 30. A feature that only occurs late therefore has a
  less-negative `mean_eval` partly because of *when* it occurs, not because of *what* it is. So
  discrimination is partly a "how late does this feature appear" signal. That is the honest reading
  of the ranking, and it lines up with Task 0's ranking-stability finding — the features that churn
  near the floor are exactly the rows 10–11 cells that only occur in long games.
- Nothing here changes the ridge fit: the intercept absorbs the constant part, and the ply-linked
  part is exactly the kind of structure the 144 active features are being asked to reproduce.
