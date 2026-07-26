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

## Task 4 — the capacity number

### Verdict

**Held-out R² = 0.976** (λ = 100, all 1152 features, 89 unseen games / 1289 positions, split seed 0).
Correlation 0.988, MAE 1230 against a label that spans ±30000. Across six split seeds the number runs
**0.93–0.98**, and λ was chosen on that same holdout — see the caveats below before quoting a
decimal.

**Decision: proceed to the runtime leaf (`nnue-trainer-aov`) and the gauntlet (`d4a.6.2`). Do not add
pairwise/region features yet.** Absolute single-cell `(row, col, state)` indicators reproduce the
hand-tuned static eval to within ~2.4% of its variance on games the fit never saw, which clears any
plausible go/no-go bar. What to watch in the gauntlet is the **residual tail**, not the mean: ~8.5%
of held-out positions are off by more than one action's worth of eval (see "Residuals" below). That
is the ceiling pairwise features would buy — after `d4a.6.2` says whether it costs games.

Regenerate:

```
./mvnw -q compile exec:java -Dexec.mainClass=com.engine.nnue_trainer.train.V3FeatureMiner \
  -Dexec.classpathScope=runtime -Dexec.args="--emit-positions /tmp/nnue_v3_positions.jsonl"
python3 -m python.v3.fit_capacity /tmp/nnue_v3_positions.jsonl --out nnue_v3_weights.json
```

### The λ sweep, top-332 vs full-1152

446 games / 6589 positions, 20% held out by game → 357 train games (5300 positions) / 89 holdout
games (1289 positions), seed 0. `p10`/`p90` are holdout residual quantiles (label units).

| λ | R² hold (top-332) | R² hold (full-1152) | R² train (full) | corr | MAE | resid p10 | resid p90 |
|---|---|---|---|---|---|---|---|
| 0 | 0.9482 | 0.9399 | 0.9890 | 0.9706 | 1272 | -513 | +284 |
| 1 | 0.9544 | 0.9559 | 0.9872 | 0.9782 | 1156 | -639 | +280 |
| 10 | 0.9670 | 0.9699 | 0.9820 | 0.9849 | 1041 | -673 | +322 |
| **100** | 0.9740 | **0.9756** | 0.9634 | 0.9879 | 1230 | -1133 | +704 |
| 1000 | 0.9400 | 0.9409 | 0.8966 | 0.9778 | 2989 | -4942 | +4461 |
| 10000 | 0.6395 | 0.6401 | 0.5815 | 0.9460 | 8803 | -13743 | +15176 |
| 100000 | 0.1426 | 0.1427 | 0.1251 | 0.8913 | 13265 | -22677 | +30378 |

Reading it:

- **λ matters, as predicted.** Each cell's 8 state columns sum to the intercept column, so the design
  is rank-deficient 144 times over. At λ = 0 the full fit overfits visibly (train 0.989 vs holdout
  0.940 — the only row where the top-332 cut *beats* the full set, because dropping 820 columns is
  itself regularization). The curve peaks at λ = 100 and falls off a cliff past 1000.
- **The top-N cut costs almost nothing.** At the peak, top-332 gives 0.9740 against full-1152's
  0.9756 — 0.0016 of R² for 71% fewer columns. Whether `aov` ships 332 or 1152 weights is a runtime
  memory question, not an accuracy one.
- **Residuals: tight middle, fat tail.** At λ = 100 the middle 80% of holdout residuals sit in
  [-1133, +704] on a ±30000 label — but MAE (1230) exceeds the p90, so the error is concentrated
  rather than spread. The absolute holdout residual quantiles say it plainly:

  | p50 | p75 | p90 | p95 | p99 | max |
  |---|---|---|---|---|---|
  | 390 | 920 | 3218 | 5686 | 12641 | 29388 |

  One turn is worth ~11085 eval units (Task 2), i.e. ~3700 per action, so **~8.5% of held-out
  positions are mispredicted by more than one action** and ~1% by three or more. The tail is where a
  linear-in-single-cells model cannot follow the hand-tuned eval's interaction terms; that is the
  honest ceiling of this representation, and it is what pairwise features would buy *if* the gauntlet
  shows it costing games. Both well-populated ply buckets fit about equally well (ply 0–9 R² 0.961
  over 888 holdout positions, ply 10–19 R² 0.984 over 389), so this is tail concentration, not an
  opening/endgame split — the 12 holdout positions past ply 20 are too few to read.
- **The number moves with the split.** Held-out R² at λ = 100, full-1152, by seed: **0.976 / 0.953 /
  0.944 / 0.962 / 0.931 / 0.940** (seeds 0–5). So quote it as **R² ≈ 0.93–0.98**, not as 0.9756.
  Seed 0 is the optimistic end of that band. The verdict does not turn on which seed — every seed
  clears any plausible go/no-go bar — but a single decimal place here is noise.
- **λ was selected on the reported holdout.** There is no third split: the sweep picks its best row
  by held-out R² and that same number is the headline, so 0.9756 is optimistically biased as a point
  estimate. The selection is nearly stable (λ = 100 wins on 5 of the 6 seeds; seed 3 prefers λ = 10
  by 0.008), which is why the seed *band* above — not the max — is the number to carry forward.

### Data-ratio caveat, with the actual counts

6589 positions against 1152 columns is **≈ 5.7:1**; restricted to the 332 above-floor features it is
**≈ 19.8:1**. (The plan guessed ~7000 positions and ~14:1 at top-500; the real corpus gives 6589
positions, and only 332 features clear the floor, so the top-N ratio is better than guessed and the
full-1152 ratio is what it is.) Better than the gate run's ~2.9:1, still modest.

More importantly, those 6589 positions come from **446 games**, and positions inside a game are
nearly collinear — the effective sample size is closer to the game count than to the position count.
That is exactly why:

- the split is **by game**, never by position;
- **only the held-out R² is quoted as evidence.** The train R² is in the table for the overfit
  diagnostic (the 0.989/0.940 gap at λ = 0) and for nothing else. A train-set R² on this data is not
  evidence of capacity;
- the seed spread above is reported rather than hidden. With 89 holdout games, which games land in
  the holdout is worth ±0.03 of R².

### `nnue_v3_weights.json`

The warm start for `nnue-trainer-aov` (engine leaf) and `nnue-trainer-1uz` (net initialization).
Caveat on the number itself: λ and the feature set are picked by maximising `r2_holdout` over the
14 sweep candidates, so the reported 0.976 is a selection-optimistic estimate, not a clean
out-of-sample one (that is why it sits *above* `r2_train`). The seed sweep bounds the optimism —
0.93–0.98 across seeds 0–5, and λ = 100 wins 5 of 6 — so the ">0.85 = enough capacity" verdict
survives it comfortably. Quote the range, not the decimal. A three-way train/validate/holdout split
would give an unbiased number if a later bead needs one.

The sweep picks (λ, feature set) on the holdout — currently λ = 100, full 1152 features — and the
fitter then **refits that model on every position** before writing the file (`"fit_on": "all"`). The
holdout's job is to measure and to choose λ; withholding a fifth of a 446-game corpus from the
artifact downstream beads initialize from would only make the initialization worse. So the `r2_*`
/ `corr_*` / `mae_*` fields describe the train-only fit at the same λ, **not** the shipped weights —
there is no held-out number for the shipped weights by construction.

```
{"meta": {"lambda": 100.0, "top_n": 1152, "n_features_total": 1152, "fit_on": "all",
          "split_seed": 0, "holdout_frac": 0.2,
          "games_used": 446, "games_train": 357, "games_holdout": 89,
          "positions_total": 6589, "positions_train": 5300, "positions_holdout": 1289,
          "r2_holdout": 0.97564, "r2_train": 0.96339, "corr_holdout": 0.98790,
          "mae_holdout": 1229.82, "bias": 9912.07},
 "weights": {"<feature_index>": w, ...}}
```

`eval_v3(board) = bias + Σ_{f active} weights[f]` over the 144 active indices, STM-relative, same
units as the hand-tuned static eval. **`weights` holds one key per *selected* feature, not per
feature** — `meta.top_n` says how many, `meta.n_features_total` how many exist. The full-1152 set
won here so the current file is dense, but a top-N winner writes only those keys, so a consumer
must read a missing index as `0.0` (which is exactly its fitted contribution), never as an error. Weights range [-5099, +3954], mean |w| = 320. Note the bias:
+9912 against a mean label of -4255, because the 144 EMPTY-state weights sum to -12277 — the
intercept and the per-cell weights are only identifiable together (the dummy-variable degeneracy),
so a consumer must apply bias and weights as a pair; neither is meaningful alone.

Sanity check on the start position: the model scores it -266 where the hand-tuned eval scores +36 —
a ~300 miss on a label that spans ±30000. (The train-only fit missed it by ~2400, because seed 0's
holdout took 89 games' worth of opening positions out of the fit; the refit on all games is why the
shipped artifact does better here.)

## Regenerating the capacity number

Two steps: mine a per-position dataset out of games.db, then fit. Both are deterministic — same DB
plus same `--seed` gives the same R².

```bash
# 1. mine. --emit-positions writes the JSONL dataset; the aggregate stats JSON is
#    byte-identical with and without the flag (V3FeatureMinerTest asserts it).
./mvnw -q compile dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
java -cp "target/classes:$(cat target/classpath.txt)" \
  com.engine.nnue_trainer.train.V3FeatureMiner /home/iv/games.db nnue_v3_feature_stats.json \
  --emit-positions /tmp/nnue_v3_positions.jsonl

# 2. fit. Sweeps lambda, fits both top-N and full-1152. --out is required to write the artifact:
#    without it the run is a pure diagnostic, so a --seed/--lambdas sweep cannot clobber the
#    committed warm start.
python3 python/v3/fit_capacity.py /tmp/nnue_v3_positions.jsonl --out nnue_v3_weights.json
```

One JSONL row per replayed position: `{"game_id": "<uuid>", "ply": P, "eval": E, "active": [144
indices]}`, `active` in row-major cell order so `active[r*12+c]` is that cell's feature id.
`game_id` is a **string** — games.db keys games by uuid, and reading it as a long silently collapsed
446 games into 173 during Task 3.

Fitter knobs (`python/v3/fit_capacity.py --help`):

| flag | default | what it does |
|---|---|---|
| `--lambdas` | `0 1 10 100 1000 10000 100000` | ridge penalty sweep. **Load-bearing, not cosmetic**: each cell's 8 state columns sum to the intercept, so the design is rank-deficient 144 times over and λ picks a point on that degenerate direction. Too low → the fit chases noise; too high → everything shrinks to the corpus mean. Best held-out was λ = 100. |
| `--top-n` | `500` | fit only the top-N features by discrimination. **Clamps** to however many cleared the support floor (332 on the current corpus), so an over-large N is not an error. Must be positive (it is a slice bound — a negative N would silently take the *worst* features). Costs ~0.002 R² vs the full 1152. |
| `--holdout-frac` | `0.2` | fraction of **whole games** held out. Never split by position — positions inside one game share nearly all features and leak. Must be in (0, 1), and an empty train or holdout side is a hard error, not a silent one-game fallback. An empty positions file is likewise a named error, not a numpy traceback. |
| `--seed` | `0` | which games land in the holdout. Worth ±0.02 R² at 89 holdout games (0.93–0.98 over seeds 0–5) — quote a range, not a decimal. |
| `--stats` | `nnue_v3_feature_stats.json` | where the discrimination ranking and support floor come from. Must be mined from the same DB as the JSONL. |
| `--out` | *(empty — writes nothing)* | where to write the sweep's best-held-out (λ, feature set), **refit on all positions**. Opt-in on purpose: the documented methodology is a λ/seed sweep, and a default of `nnue_v3_weights.json` would let any exploratory run silently replace the committed warm start. |

Tests: `python3 -m unittest discover -s python/v3 -p "*_test.py"` (fitter) and `./mvnw test`
(miner, stats-artifact invariants, HTML splice, eval sign convention). Run `./mvnw spotless:apply`
before pushing — CI checks formatting before it runs tests.
