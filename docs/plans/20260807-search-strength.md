# Search-strength improvement plan — bead nnue-trainer-1jh.2

**Goal:** make `GoBotSearcher` substantially stronger at equal wall-clock, independent of leaf-eval
quality. Search depth is the proven lever in this repo: node-budget GoBot search + hand-tuned eval
already beats the original GoBot **6-0** (`docs/plans/20260724-phase3-auto-retrain-loop.md:10`),
and the Phase-1 finding was that eval parity alone still lost 0-5 — the search was the whole gap
(`docs/plans/20260723-phase1-port-gobot-search.md:5-8`).

**Status: design + audit only.** No benchmarks were run (host is training nets). Every claim below
cites the line it was read from. Expected gains are reasoned estimates to be confirmed by the
measurement plan, not measurements.

## Context: what the search is today

- Iterative-deepening minimax with PVS null-window scouts and a TT
  (`GoBotSearcher.java:332-478`), root-relative frame (`maximizing = currentPlayer == root`,
  `GoBotSearcher.java:429`), no quiescence, no reductions, no extensions, single-threaded.
- 3 actions per turn (`GoState.java:26`), mover flips only when `movesLeft` hits 0 or the mover is
  eliminated (`GoState.java:349-352`). Average branching ≈ 29.6 children per node measured on real
  positions (`docs/nnue-v3-gauntlet.md:36`); neutral-pair turns are capped at 48 strategic pairs
  (`GoPosition.java:24`).
- Real NPS (12x12, 60k-node budget, `docs/nnue-v3-net-runtime.md:153-159`): hand-tuned leaf ~65k
  NPS (920 ms/move), v3-net H=32 leaf ~175k NPS (344 ms/move). Note the doc's own observation:
  *"move generation, `apply`, and the TT are a large share of a node"*
  (`docs/nnue-v3-net-runtime.md:169-171`) — that share is exactly what this plan attacks.
- Production budget is a flat 1s/move (`GoBotSearcher.java:254`); `chooseWithDeadline` returns the
  last fully completed iteration (`GoBotSearcher.java:281-293`) — the bead 0dj.7 fix is assumed.

**Parity constraint.** `GoBotSearchParityTest` pins `chooseDepth` to GoBot's exact moves/scores
(412 fixture records, `docs/plans/20260723-phase1-port-gobot-search.md:154-157`). Any strength
improvement diverges from GoBot by design. Resolution: a per-searcher `enhanced` boolean, set by
`chooseNodeBudget`/`chooseWithDeadline` (the strength paths, used by `GauntletMatch` and the live
bot) and left false by `chooseDepth` (the parity oracle). One flag, no parallel code paths — each
enhancement below is a small `if (enhanced)` at its site. The fixture keeps passing; the gauntlet
measures the enhanced paths.

## Shared measurement harness (build first, it gates everything)

1. **Fixed-time gauntlet mode.** `GauntletMatch.Config` has `nodeLimit`/`fixedDepth` but no
   wall-clock mode (`GauntletMatch.java:60-80`, dispatch at `GauntletMatch.java:180-185`). Add
   `Config.moveMillis` (0 = off) → `chooseMove` calls
   `GoBotSearcher.chooseWithDeadline(state, now + moveMillis)`. NPS-type changes (items 1, 2, 4)
   are invisible at fixed nodes and can only be scored at fixed time; ordering-quality changes
   (items 3, 6, 7) are cleanest at fixed nodes. Every item below is measured **both** ways.
2. **Sample size.** 400 games (200 color-flipped opening pairs via the existing seeded epsilon
   openings, `GauntletMatch.java:115-127, 150-153`). At p≈0.5 the 95% CI on win% is ±4.9%, i.e.
   ~±35 Elo resolution — adequate for the effects claimed below; run 800 for anything that lands
   inside the CI.
3. **Search counters probe.** Add cheap per-searcher counters (no allocation on the hot path):
   fail-high index histogram (index of the child that caused the cutoff in
   `GoBotSearcher.java:432-468`), TT probe/hit/cutoff counts (`GoBotSearcher.java:396-420`), PVS
   re-search count (`GoBotSearcher.java:439-447`), children materialized vs children searched.
   Dump behind a system property, driven over ~500 games.db positions exactly like
   `V3OrderingProbe` (`docs/nnue-v3-gauntlet.md:79`). This is the before/after evidence for items
   1-3 and the go/no-go gate for item 7.

Baselines to record once: hand-tuned leaf and v3-net leaf, fixed 60k nodes and fixed 250 ms and
1000 ms, current master. All later rows compare against these.

---

## Ranked items (expected-Elo-per-effort, best first)

### 1. Stop materializing all ~30 children at cut-nodes (staged move generation)

**Today.** `orderedChildren` applies **every** legal action to build its child state before any is
searched — `pos.applySearch(action)` inside the enumeration loop (`GoBotSearcher.java:543-547`) —
because three of the ordering features need the child (`next.gameOver()`, `activeCount(next)`,
`next.currentPlayer()`, `GoBotSearcher.java:552-565`). Each `applySearch` copies the 144-cell grid
(`GoState.java:117-130`) and then runs `eliminateStuckPlayers` → `hasMove` → a full-board
connectivity BFS **per player** (`GoState.java:345`, `GoState.java:472-503`, BFS at
`GoState.java:401-435`). So a single interior node pays ~30 grid copies plus ~60 flood fills up
front. Alpha-beta then typically cuts after the first few children — with the TT move ordered
first (+10M, `GoBotSearcher.java:549-551`) most cut-nodes never look past child 1. At a cut-node,
~90% of the node's dominant cost is thrown away.

**Design (two stages, stop after A if the counters say it's enough).**

- **Stage A — TT-move-first fast path** (small): in `minimax`, when the probe returned a
  `bestAction`, apply and search *only that child* before generating the rest. On cutoff, store
  and return — one `applySearch` instead of ~30. Only if it fails to cut, fall through to today's
  full `orderedChildren` (skipping the TT move). Ordering semantics are unchanged — the TT move is
  already sorted first — so this is pure cost removal. With iterative deepening, the large
  majority of cut-nodes carry a TT move.
- **Stage B — static ordering for the rest** (medium, only if the item-3 counters show many
  cut-nodes have no TT move or cut on child 2+): replace the apply-dependent ordering features
  with parent-state predictors — capture bonus already reads the parent (`GoBotSearcher.java:558-561`);
  turn-continuation is `movesLeft > 1` for a `MoveAction`; win/elimination detection restricted to
  moves adjacent to an opponent base region instead of full `applySearch`. Then apply children
  lazily as the loop reaches them.

**Expected gain.** Largest single-thread wall-clock lever in the file. If cut-nodes dominate and
cut at index ~1-2 (the histogram will say), node cost drops by roughly 2-3x → NPS ×2-3 → ~1
extra action-ply at equal time. Given that the whole 6-0 result came from a search-depth edge,
estimate +70-150 Elo at fixed time. Fixed-node margin should be ~0 (it changes cost, not the
tree) — that is itself a correctness check.

**Measurement.** Counters: children-materialized / children-searched ratio before vs after.
Gauntlet: fixed-time 400 games (expect clear win), fixed-node 400 games (expect ~0, sanity).

**Risks.** Stage A must reproduce `preservingChildren` semantics at the root (root-only,
`GoBotSearcher.java:341` — unaffected since Stage A is in `minimax`). Stage B changes move order →
different trees; keep it behind `enhanced` and measure separately from Stage A.

### 2. Transposition table overhaul (probe rule, replacement, persistence, structure)

**Verified first:** the key **does** include the turn structure — `GoState.hash()` folds in
`current`, `movesLeft`, per-player `active` and `neutralUsed`, then every cell
(`GoState.java:539-561`). No aliasing from the 3-action turn; in fact within-turn action
permutations (place A then B vs B then A) reach the same state *at the same ply* and are already
caught. The real defects are elsewhere:

- **Ply-exact probe.** `minimax` only uses an entry when `entry.depth >= depth && entry.ply == ply`
  (`GoBotSearcher.java:398`). Any transposition reached at a different distance from the root
  misses (e.g. capture/recapture cycles, ±2 plies), and — decisively — it makes cross-move TT
  reuse worthless, because after playing one action every stored ply is off by one.
- **TT thrown away every move.** `choose`/`chooseNodeBudget` build a fresh searcher per call
  (`GoBotSearcher.java:279`, `GoBotSearcher.java:311`) with a new empty table
  (`GoBotSearcher.java:130`). With 3 actions/turn, the position after our chosen action was the
  most-searched subtree of the previous search; all of it is recomputed from scratch.
- **Unconditional overwrite.** `store` is `table.put` (`GoBotSearcher.java:199-202`), so a shallow
  entry clobbers a deep one for the same key.
- **`HashMap<Long, TableEntry>`** (`GoBotSearcher.java:117`): boxed key + entry allocation per
  store, per node — GC pressure and cache misses, unbounded growth, and unusable for lazy SMP.

**Design.**

- Replace with a fixed-size array TT: `long[] keys` + `long[] data`, 2^21-2^23 entries (32-128 MB),
  index = `key & mask`. Pack `TableEntry` into one long: score is stored as `int`
  (`TableEntry.java:16`, cast at `GoBotSearcher.java:476`) → 32 bits, depth 6 bits
  (`MAX_DEPTH = 64`, `GoBotSearcher.java:42`), flag 2 bits, generation 6 bits, best action 18 bits
  (a `MoveAction` is one cell index 0-143 = 8 bits; a `PlaceNeutralsAction` is two cell indices;
  tag bit). `maxN`'s 4-score entries stay in the old map — the 1v1 strength path never runs it
  (`GoBotSearcher.java:354-356`).
- Drop `ply == ply`; keep root-relative scores (valid across plies within one search — only mate
  scores encode ply). Store mate scores ply-adjusted to the node (`MATE_SCORE - ply` rebased to
  node distance on store, re-based to the probing node's ply on probe), the standard mate-in-TT
  correction; terminal scoring is `GoBotSearcher.java:719-724`.
- Replacement: depth-preferred with generation aging (bump generation per `choose*` call; replace
  if same key, older generation, or shallower depth). Two-slot bucket (depth-preferred + always)
  if the probe counters show hit-rate loss from single-slot.
- Persistence: keep one searcher per game side and reuse it across moves (bump generation instead
  of clearing). Root-relative scores stay valid across moves for one side because `root` is that
  side every move; in `GauntletMatch` the two sides must own **separate** searcher instances (the
  leaf config already swaps per mover, `GauntletMatch.java:144-147`). Requires new instance-level
  entry points (`chooseNodeBudget`/`chooseWithDeadline` on an existing searcher); the static ones
  stay as one-shot wrappers.

**Expected gain.** Three compounding effects: allocation-free probe/store (NPS, worth 10-30% given
the docs already name the TT as a large node cost, `docs/nnue-v3-net-runtime.md:169-171`),
cross-ply and cross-move hits (free depth — the previous move's PV subtree seeds every iteration),
and no deep-entry clobbering. Estimate +50-100 Elo at fixed time, and a real fixed-**node** gain
too (better hits → better ordering → smaller trees). Effort: medium.

**Measurement.** Counters: TT hit rate and TT-cutoff rate before/after; nodes-to-depth-d on fixed
positions. Gauntlet fixed-time and fixed-node, 400 games each. Also rerun
`GoBotNodeBudgetParityTest` expectations only for the non-enhanced path (must be unchanged).

**Risks.** Mate-score rebasing is the classic bug source — cover with a unit test (mate found via
TT at shifted ply must keep the shorter-mate preference). Packed-action encode/decode needs a
round-trip test (`PlaceNeutralsAction.equals` is unordered, `PlaceNeutralsAction.java:16-24` —
normalize on encode). Persistence across moves must key generations correctly or stale entries
from 2 moves ago outrank fresh ones.

### 3. Killer moves + history heuristic (ordering for a 30-wide game)

**Today.** Ordering is TT move (+10M), immediate win (+1M), elimination delta (+100k), capture
(+10k), turn-continuation (+100), ties in stable board order (`GoBotSearcher.java:549-569`).
That's a decent static scheme, but there are **no killers and no history** — nothing learned
during the search. At ~30-wide branching, quiet cut moves (the common case: most cutoffs are
positional placements, not captures) sit in board order, so the same refutation is rediscovered
at every sibling node.

**Design.**

- **Killers:** two `Action` slots per ply (`Action[MAX_DEPTH][2]`), updated on fail-high with a
  non-capture; ordered right after the TT move (+5M say). Actions have value `equals`
  (`MoveAction.java:14-18`), so slot comparison is trivial. In staged generation (item 1), killers
  form a stage-A.5: validate with `legalMove` (`GoState.java:381-399`) before applying.
- **History:** `int[2][144]` indexed by (mover-1, target cell index) for `MoveAction`s, bumped by
  `depth*depth` on fail-high, halved periodically; added into the order score below the capture
  bonus. Neutral pairs are excluded (rare, 21 of 412 fixture actions were neutral placements,
  `docs/plans/20260723-phase1-port-gobot-search.md:87`).
- Both live behind `enhanced`; per-searcher state (works unchanged under item 4's SMP since each
  thread owns its killers/history).

**Expected gain.** In chess, killers+history are worth a large fraction of the move-ordering
budget; here the branching is similar and the current quiet ordering is "board order", i.e. close
to worst-case. Expect the fail-high-index histogram mass to shift markedly toward index 0-1,
shrinking the effective branching factor → deeper equal-time search **and** a fixed-node gain.
Estimate +40-80 Elo. Effort: small — this is the best pure Elo-per-line item; it ranks below items
1-2 only because they are prerequisites for its full effect (ordering gains multiply with cheap
nodes and good TT hits).

**Measurement.** Fail-high index histogram (item 3 of the harness) before/after — this is the
direct cutoff-index statistic the bead asks for. Gauntlet fixed-node 400 (primary — pure tree
quality) + fixed-time 400.

**Risks.** Low. History aging bugs show up as mid-game weakening — the histogram catches it.

### 4. Lazy SMP (8 prod cores, currently 1 used)

**Today.** Strictly single-threaded; one searcher, one thread, `HashMap` TT.

**Design (minimal, on top of item 2's array TT — do not attempt with the HashMap).**

- N = 6-7 worker threads (leave a core for the event loop). All run the same
  `chooseWithDeadline`-style ID loop on the **same shared array TT**; helpers start at staggered
  depths (thread i starts at depth 1 + (i % 2), the classic lazy-SMP skew) and simply keep
  iterating until the deadline. The main thread's last completed iteration is the answer —
  identical contract to today's `chooseWithDeadline` (`GoBotSearcher.java:281-293`).
- TT thread-safety: `TableEntry` today is immutable (`TableEntry.java:11-24`), which would make a
  `ConcurrentHashMap` *safe*, but slow and allocation-bound. The packed-long array TT uses the
  standard lockless XOR trick (`keys[i] = key ^ data[i]`; a torn write fails the probe's
  key-check) — no locks, benign races, at worst a wasted probe. Per-thread: node counters,
  killers, history. The volatile `defaultLeaf` snapshot is already read once per searcher
  (`GoBotSearcher.java:186-190`), fine. `HandTunedEval.staticEval` and `V3Eval.evaluate` are pure
  reads of loaded weights — confirm no lazy-init mutation before enabling (the v3 evaluators load
  behind `static volatile` double-checked locks per `docs/nnue-v3-runtime.md:24-26`, safe).
- Off in `GauntletMatch` fixed-node mode (nondeterministic node accounting would break the
  reproducible gate, `GauntletMatch.java:23-27`); on for fixed-time gauntlets and the live bot.

**Expected practical speedup.** Lazy SMP is well-documented at ~1.6-2.5x effective (not Nx) for
6-8 threads — the shared TT turns redundant work into ordering/hits. One real hazard here:
`GoState.apply` allocates a fresh grid per node (`GoState.java:117-130`), so 7 threads multiply
allocation rate ~7x; GC may cap scaling below the textbook number (item 1 reduces exactly this
allocation, another reason it lands first). Estimate +60-120 Elo at 1s/move. Effort: medium-high —
the largest single lever after the single-thread items, but it multiplies them, so it goes last.

**Measurement.** Fixed-time only: 400 games SMP-on vs SMP-off at 250 ms and 1000 ms, idle
machine. Also record depth-reached distribution per move (should rise ~1 action-ply).

**Risks.** Any hidden shared mutable state (audit: `GoOpeningBook` is stateless,
`GoOpeningBook.java:23-25`; `GauntletMatch.applyLeaf`'s process-global leaf swap is per-move
sequential, fine as long as SMP threads are joined before the next move). Nondeterminism
complicates debugging — keep a single-thread switch and never run SMP in parity tests.

### 5. Time management (what the flat 1s still leaves on the table)

**Today.** Flat `PRODUCTION_BUDGET_MILLIS = 1000` per action (`GoBotSearcher.java:254`);
`running()` hard-stops mid-iteration (`GoBotSearcher.java:209-214`) and the whole partial
iteration is discarded (`SearchIncomplete` → `break`, `GoBotSearcher.java:283-287`), including
completed root moves.

**Design.**

- **Use the partial iteration.** In `atDepth`, root children are searched sequentially with a
  rising alpha (`GoBotSearcher.java:348-376`). If the deadline hits after root child k ≥ 1
  completed, the best of those k is at least as well-searched as the previous iteration's answer
  for those moves — and child 0 is the previous PV (TT-ordered, `GoBotSearcher.java:336`). Catch
  `SearchIncomplete` at the root loop, and if child 0 completed, return the partial best instead
  of discarding. This is the standard "PV move completed ⇒ usable" rule.
- **Soft/hard deadline.** Don't start iteration d+1 if elapsed > ~55% of budget (a new iteration
  costs ~EBF× the previous one and will almost surely be cut). Recovers the ~half-budget wasted
  on doomed iterations.
- **Turn-aware budgeting** (optional): the first action of a turn has the neutral branch and the
  widest choice; actions 2-3 usually continue a plan and start from a warm TT (item 2's
  persistence). Split a per-turn budget e.g. 40/30/30 instead of flat thirds only if the
  fixed-time gauntlet shows a gain; otherwise skip (YAGNI).

**Expected gain.** Soft/hard + partial-iteration reuse is worth ~+20-40 Elo in engines with
similar ID structure; here the discard-everything behavior makes the ceiling a bit higher.
Effort: small.

**Measurement.** Fixed-time gauntlet 400 games; also log (budget used / budget) and depth-reached
distributions — expect mean depth up ~0.3-0.7 ply at equal budget.

**Risks.** Partial-iteration selection must never trust a root move whose scout failed low with a
bound score — `RootMove.exact` already flags this (`GoBotSearcher.java:352-368`); the rule "only
if child 0 completed, and only prefer a later child on an exact score" keeps it safe.

### 6. Aspiration windows

**Today.** Every iteration reopens the full window `(-INF, +INF)` (`GoBotSearcher.java:346-347`)
despite having the previous iteration's score in hand.

**Design.** In the ID loops, for depth ≥ 3 start `atDepth` with `alpha = prev - δ`,
`beta = prev + δ`; on fail-low/high, widen that side (δ → 4δ → full). δ in hand-tuned units:
start at ~1500 — the median best-vs-2nd sibling gap is 1299 (`docs/nnue-v3-gauntlet.md:46-48`),
so ±1500 brackets a typical move swing. `atDepth` needs an `(alpha, beta)` overload; fail
detection is `bestScore <= alpha || bestScore >= beta`. Behind `enhanced`.

**Expected gain.** Modest at 30-wide branching (~5-10% node reduction typical), +10-25 Elo.
Effort: small (an afternoon). Ranked here because it's nearly free but strictly smaller than
items 1-5.

**Measurement.** Nodes-to-depth-d on fixed positions (should drop, with re-search rate < ~15%);
fixed-node gauntlet 400.

**Risks.** Re-search storms if δ is too tight for this eval's volatility — the counter (fail
rate) gates the rollout. NNUEV2's WDL-scaled leaf uses different units
(`GoBotSearcher.java:69-82`), so δ must scale by leaf mode or aspiration stays
hand-tuned/v3-only (v2 is dead anyway, 0-24 per `docs/nnue-v3-gauntlet.md:17`).

### 7. Late-move reductions — gated on the item-3 histogram

**Today.** Every child is searched at `depth - 1` regardless of ordering position
(`GoBotSearcher.java:436-447`).

**The turn-structure interaction (flagged).** Depth is counted in *actions*, and the mover flips
only on turn-ending actions (`GoState.java:349-352`). A reduction changes the leaf's parity: a
leaf reached one action earlier can land on the *other side's* turn-fragment, and
`HandTunedEval.staticEval` takes `currentPlayer`/`movesLeft` for tempo
(`GoBotSearcher.java:650-651`), so reduced and unreduced siblings get systematically different
tempo terms — noise injected exactly where LMR compares them. Mitigation: prefer reductions that
keep the reduced leaf inside the same side's turn-fragment (or reduce by a full turn's worth of
actions), and always verification-re-search at full depth on fail-high, which bounds the residual
bias.

**Design (only if the fail-high histogram after item 3 still shows a fat tail past index ~6).**
For move index ≥ 4, non-capture, non-killer, not TT move, depth ≥ 3: search the null-window scout
at `depth - 2`; on `score > alpha`, re-search at `depth - 1` full window. No reduction at root or
on the PV child. Behind `enhanced`.

**Expected gain.** Potentially large at 30-wide branching (LMR is the biggest tree-shrinker in
chess engines), but it is the only item here that can *lose* Elo if the eval's sibling ordering
is weak — and this game's evals have exactly that history (`docs/nnue-v3-gauntlet.md:40-53`).
Estimate +30-100 Elo or negative; hence gated and last. Effort: small code, real tuning cost.

**Measurement.** Fixed-node gauntlet 400 (primary; LMR must win at equal nodes or it's just
mis-pruning), then fixed-time. Re-search-rate counter.

---

## Explicitly not doing (lazy by intent)

- **Negamax rewrite** of the root-relative frame: pure refactor risk, zero strength
  (`docs/plans/20260722-fix-negamax-strength-regression.md` is the cautionary tale of touching
  score frames), and it would complicate parity.
- **Quiescence / singular extensions / multi-cut:** speculative frameworks; nothing in the
  counters yet says horizon effects are the binding constraint. Revisit only if item 7 stalls.
- **Deeper opening book:** the 3-move wedge (`GoOpeningBook.java:76-80`) already covers the only
  fully-forced phase; beyond it, search at +1 ply (from items 1-4) beats any hand-rolled line.
- **Endgame territory adjudication** (declare wins early when regions are sealed —
  `GoState.outcomeWinner` exists, `GoState.java:177-213`): detecting "no contested frontier"
  needs its own per-node flood-fill machinery; cost-benefit unproven. Noted for later, not built.
- **Incremental Zobrist:** `hash()` rescans the board per node (`GoState.java:539-561`), but at
  ~150 cheap ops it is dwarfed by the ~60 BFS flood fills item 1 removes. Profile after item 1;
  only then decide.

## Rollout order and why

Harness first (fixed-time mode + counters), then **1 → 2 → 3** (each multiplies the next: cheap
nodes × warm TT × good ordering), then **5, 6** (small, independent), then **4** (SMP multiplies
everything before it and needs item 2's TT), then **7** (gated on item 3's histogram). Each item
ships behind the single `enhanced` flag, is gauntleted at 400+ games fixed-node *and* fixed-time
against the previous step, and keeps `GoBotSearchParityTest` green on the untouched oracle path.

Cumulative honest estimate if items 1-6 land near mid-range: ~3-5x effective search speed at
equal wall-clock (~+1.5-2 action-plies), which by this codebase's own history (6-0 with a search
edge, 0-5 without one) is the difference class the bead is asking for.
