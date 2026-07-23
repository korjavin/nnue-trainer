# Strategy: Clone GoBot in Java (parity floor) → then NNUE on top

Status: **documented, not scheduled for execution.** This reframes the roadmap after the
2026-07-22 overnight results. Decision on whether/when to execute is the user's.

## Why this pivot

We spent significant effort trying to *learn* our way to GoBot parity and hit wall after wall:

- **`ntd.8`** — distilled GoBot's static eval into the NNUE at val MSE 0.015 (98.5% fit) →
  **still 0/7 in play.** Reproducing the eval did not transfer strength.
- **`raz.2.5`** — our search is now correct (negamax, depth 8–12) but deeper search adds no
  wins → not search-depth-bound.
- **`raz.2.4`** — our TT/PVS/quiescence "upgrade" adds **zero** strength over naive alpha-beta
  (it's feature-complete but untuned).
- **`ntd.9`** — one TD-leaf value-iteration pass (λ sweep) → all **0/5** vs GoBot; root cause
  self-play distinct-game ratio 0.019 (degenerate data).

**Conclusion:** GoBot's moat is its **co-designed, SPSA-tuned SEARCH stack**, not its static
eval. We essentially already have its eval (distilled ≈98.5%) and lose anyway. Learning is
open-ended; **porting is deterministic engineering with a clear done-condition.** GoBot's
source is fully available. Do the deterministic thing first.

## Key correction to "just copy the eval"

Porting only `evaluate.go` and running it on *our* search would very likely reproduce the
same 0/7 we already have — because the gap is the search, not the eval. The eval and search
are co-designed. So the port target is the **eval + search stack**, verified until the Java
clone *plays like* GoBot.

## The plan (three phases)

### Phase 0 — Cheap decisive experiment (~hours)
Port GoBot's `evaluate.go` **exactly** to Java (removes the distillation's 1.5% approximation
as a variable). Run it with our *current* search, re-measure vs GoBot (5–10 games).
- Expected: still ~0/5 → **proves the moat is the search**, cleanly, and directs the effort.
- If it jumps: the distillation error mattered — informs the NNUE side.
Either way it's a fast, high-information read before committing to the bigger port.

### Phase 1 — Port GoBot's search stack → parity floor
Port/match GoBot's search: SPSA-tuned parameters, quiescence, move ordering, opening book,
TT/PVS behavior. Done-condition: a `SearchAB`-style test shows the **Java clone matches GoBot
in play** (draws/holds even, not 0/12). Deliverable: a deterministic GoBot-parity Java bot.
- This is the **guaranteed parity floor** — de-risks the whole project. No ML required.
- Reuses the game-rules port we already have (Board/MoveGenerator/etc.).

### Phase 2 — NNUE on top (the actual research, now on a real rig)
With the search fixed to the parity clone, swap in a *learned* eval and measure whether it
**beats the hand-tuned eval** — search held identical on both sides, so any win/loss is purely
the eval (the `SearchAB` isolation, but against a GoBot-strength reference). Train against the
clone as opponent/reference.
- This is where NNUE finally has a clean, search-matched testbed and a strong opponent.

## Honest caveat

Beating a hand-tuned eval *with* NNUE is unproven and hard — the label-noise ceiling (`ntd.6`)
and virusgame's own experiment log (supervised distillation "capped/negative") both warn the
learned eval may not exceed the teacher. So clone-first is worth it primarily as **(a)** a
guaranteed strong shippable bot and **(b)** the launchpad/testbed for the NNUE experiment —
not because NNUE-beats-teacher is a sure thing. Phase 2 may conclude "hand-tuned wins"; that's
still a clean, informative result and we keep a parity bot either way.

## Relation to existing work
- Supersedes the "learn to parity from scratch" framing behind `ntd.7`/`ntd.9` (those stay as
  the RL track if we later want NNUE-exceeds-teacher; not the near-term path).
- `raz.2.4` (search adds no strength) is subsumed — the clone's search replaces ours.
- Uses the reliable `eval_java_vs_go.py` harness (post-#57) and `SearchAB` as the gates.
