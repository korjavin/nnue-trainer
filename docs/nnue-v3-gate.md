# NNUE v3.0 gate — feature preview

> `docs/nnue-v3-design.md` does not exist on this branch, so this doc holds the gate section the
> plan asked for (`docs/plans/20260725-v3-feature-preview.md`, Task 6).

## The gate

Before any v3 model work (bead `nnue-trainer-d4a.6.1`: mine + ridge-distill + capacity R²), the
candidate v3 features must be **eyeballed and approved by the owner**. Nothing is trained here —
there are no weights in this artifact.

- Preview page: [`docs/nnue-v3-feature-preview.html`](nnue-v3-feature-preview.html) — 12x12 board
  heatmaps per cell-state plus a sortable ranked table. Self-contained, open it straight from disk.
- Mined data: [`nnue_v3_feature_stats.json`](../nnue_v3_feature_stats.json) — the same numbers the
  page inlines.

A v3 feature is an absolute `(row, col, cell-state)` on a fixed 12x12 board, state normalized to the
side-to-move's perspective via `PatternContract.getSymbol`. Ranking is by **eval-discrimination**
(`|mean_eval(feature active) − baseline_mean_eval|`) above a support floor — *not* by frequency.
Frequency ranking was the v2 bug: it promoted the most common shapes, which are the least
informative, and v2 lost the gauntlet 0-24.

Gate outcome: approve → d4a.6.1 proceeds; reject → the feature design is revised (e.g. pairwise or
region features) before any model spend.

## Regenerating

Mine the stats from a games.db (positions = board before each turn, STM-relative eval, `movesLeft`
fixed at 3):

```bash
./mvnw -q compile dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
java -cp "target/classes:$(cat target/classpath.txt)" \
  com.engine.nnue_trainer.train.V3FeatureMiner /home/iv/games.db nnue_v3_feature_stats.json
```

CLI: `V3FeatureMiner [db-path] [out-path] [--min-support N]`, defaulting to `/home/iv/games.db` and
`nnue_v3_feature_stats.json`. Only 12x12 games are used; others count as a `wrong_board_size` skip.

`--min-support N` sets the support floor. Default is `max(30, positions / 100)` (~1% of replayed
positions, never below 30); features below it keep their stats but get `rank = -1` and
`below_support_floor: true`. `meta.support_floor_source` records whether the floor came from the
`default` or the `flag`.

Refresh the HTML's inlined `DATA` from the JSON — it is verbatim JSON between the `DATA:BEGIN` and
`DATA:END` comment markers:

```bash
{ sed -n '1,/DATA:BEGIN/p' docs/nnue-v3-feature-preview.html
  printf '<script>const DATA = %s;</script>\n' "$(cat nnue_v3_feature_stats.json)"
  sed -n '/DATA:END/,$p' docs/nnue-v3-feature-preview.html
} > /tmp/preview.html && mv /tmp/preview.html docs/nnue-v3-feature-preview.html
```

`V3FeaturePreviewHtmlTest` then checks the page stays self-contained (no remote references) and that
its headline numbers still match the JSON.
