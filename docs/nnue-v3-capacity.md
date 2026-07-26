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
