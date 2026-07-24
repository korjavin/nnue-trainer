# v2 corpus: territory-based winner labeling (bead nnue-trainer-d4a.3.5)

## Overview
The v2 multi-size self-play raw corpus is 94.4% draws. In
`SelfPlayGenerator.generate()` (the raw/negamax path) the game winner is decided
by `determineWinner(board)`, which checks ONLY base survival and returns 0
("draw") whenever both bases are alive. Real Virus games end by board-fill /
no-moves and are decided by the game's real rule (the last player still able to
move wins; territory/cell-count is the tiebreak) — a true draw (equal territory)
is rare. Games that hit the `maxTurns` cap also leave `winner=0` → draw. Result:
every non-12x12 game and all turn-capped games are mislabeled draw, so the corpus
carries a useless 0.5 label and a model learns no win/loss signal.

Fix: reuse the canonical game engine (`GoState`, which already implements the
real, size-general rules used by the 12x12 negamax path) to decide the outcome —
do NOT hand-roll a territory counter that could disagree with the real game.
Expose one canonical terminal-outcome method on `GoState` and route every winner
decision in the raw `generate()` path (including the `maxTurns` cutoff) through
it. Only a genuine equal-territory tie stays 0 → 0.5.

## Context (from discovery)
- Files/components involved:
  - `src/main/java/com/engine/nnue_trainer/search/gobot/GoState.java` — canonical
    rules. `winner`/`over` are only set by `finishIfTerminal()` during `mutate()`;
    `fromBoard()` builds a state with `winner=0, over=false`, so
    `GoState.fromBoard(board).winner()` is ALWAYS 0. A new public query is needed.
    `active(p)` == base intact for a `fromBoard` snapshot; `hasMove(p)` (private)
    tells if a player has a legal move; `cells` gives ownership for territory.
  - `src/main/java/com/engine/nnue_trainer/train/SelfPlayGenerator.java` — raw
    `generate()` path. Winner set at: line ~287 `determineWinner(board)` (terminal
    by base), line ~289 `3-currentPlayer` (current stuck), line ~325
    `determineWinner(board)` (terminal by base after a move); `maxTurns` loop exit
    leaves `winner=0`. `determineWinner` (line ~542) is base-survival-only.
    `toRawPosition` maps `winner==0 → wdl 0.5`.
  - `src/test/java/com/engine/nnue_trainer/train/SelfPlayGeneratorTest.java` — add
    labeling test; `GoState.outcomeWinner()` is directly testable (public).
- Related patterns: the 12x12 `generateViaGoBot`/`playGoBotGames` path already
  uses `state.winner()` correctly because it plays THROUGH `GoState.apply`.
- Constraint: keep the change localized to winner/labeling logic — `SelfPlayGenerator`
  is also edited by bead ox1 on master (diversity). Base branch: `nnue-v2-integration`.

## Development Approach
- Regular (code first, then a focused labeling test).
- Reuse `GoState`'s real rules; no parallel territory implementation.
- Keep `determineWinner`'s base-destruction result decisive (no regression on the
  12x12 base-ending labels that were already correct).
- Run `./mvnw test` after the change; all existing tests must stay green.

## Testing Strategy
- Unit test: `GoState.outcomeWinner()` — (a) both bases alive, one player has more
  territory and the other is stuck → territory/elimination winner, NOT auto-draw;
  (b) genuine equal-territory position with both stuck → 0 (maps to wdl 0.5).
- No e2e tests in this project for this path.

## Progress Tracking
- Mark completed items `[x]` immediately.
- ➕ new tasks, ⚠️ blockers.

## Implementation Steps

### Task 1: Add canonical terminal-outcome to GoState
- [ ] add `public int outcomeWinner()` to `GoState.java`: for players 1..players,
      count those that are `active(p) && hasMove(p)`; if exactly one survives,
      return it (real elimination rule — last player able to move wins). Otherwise
      (both able to move at a turn cap, or both simultaneously stuck) decide by
      territory: more owned cells wins; equal → return 0 (genuine tie).
- [ ] add a private `ownedCells(int player)` helper counting `cells[i].owner==player`.
- [ ] javadoc: note this is the size-general game outcome used to label self-play
      positions, and that base-destruction is already covered (a lost base makes a
      player inactive, so the survivor wins).
- [ ] write `GoStateTest.outcomeWinner` (or extend existing) covering: territory
      decides a both-bases-alive fill ending; equal territory → 0; base destroyed
      for one player → the other wins.
- [ ] `./mvnw -q test -Dtest=GoStateTest` (or full) — must pass before Task 2.

### Task 2: Route SelfPlayGenerator raw winner decisions through GoState
- [ ] add a private helper `canonicalWinner(Board board, int currentPlayer)` that
      returns `GoState.fromBoard(board, currentPlayer, GoState.ACTIONS_PER_TURN,
      new boolean[2]).outcomeWinner()`.
- [ ] replace both `determineWinner(board)` calls (terminal-by-base sites) with
      `canonicalWinner(board, currentPlayer)`.
- [ ] route the current-stuck branch (`winner = 3 - currentPlayer`) through
      `canonicalWinner` too, so a simultaneous board-fill is decided by territory
      rather than always awarding the opponent.
- [ ] after the `for (turn ...)` loop, if `winner == 0`, set
      `winner = canonicalWinner(board, currentPlayer)` so turn-capped games are
      decided by territory instead of defaulting to draw.
- [ ] delete the now-unused `determineWinner(Board)` method.
- [ ] add a `SelfPlayGeneratorTest` labeling test: a board-fill-with-both-bases-alive
      position is labeled by territory (winner != 0 / wdl != 0.5), and a genuine
      equal-territory tie stays wdl 0.5 (via `toRawPosition`/`computeTarget` or
      `GoState.outcomeWinner` directly).
- [ ] `./mvnw test` — full suite must pass before Task 3.

### Task 3: Verify acceptance criteria
- [ ] confirm raw winner determination uses the canonical size-general outcome on
      all board sizes and base-destruction stays decisive.
- [ ] confirm a genuine equal-territory tie maps to 0.5.
- [ ] `./mvnw test` green (existing + new tests).
- [ ] `./mvnw -q spotless:check` (or the project's formatter/linter) clean.

## Technical Details
- `outcomeWinner()` reads only `cells` + `active(p)` + `hasMove(p)`; whose turn it
  is / `movesLeft` / `neutralUsed` do not affect the outcome, so `canonicalWinner`
  may pass any `currentPlayer`.
- `MoveGenerator.getLegalActions` empty ⇔ `GoState.hasMove` false for that player
  (moves are not gated by neutral-placement), so the stuck-branch reroute is
  consistent with the existing empty-actions detection.

## Post-Completion
*No checkboxes — external/manual, run outside ralphex.*

**Corpus regeneration** (CPU job, run in background after tests pass):
- `scripts/gen_v2_corpus.sh` with a real `NUM_GAMES` across sizes 12x12/9x9/7x7/5x5/5x7.
- Report per-size draw rate + win/loss balance; expect draw rate to collapse from
  ~94% to single digits with a roughly balanced win/loss split per size.
