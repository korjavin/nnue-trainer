# NNUE v2: Pattern-Based Variable-Board Evaluation

This document is the reference specification for the NNUE v2 workstream.
NNUE v2 is intentionally separate from the current fixed 12x12 whole-board
NNUE v1 path. Work for this design should target the `v2` branch first, not
`master`, so v1 work can continue independently until v2 is proven.

## Goal

Build a variable-board-size NNUE evaluator for Virus by replacing absolute
coordinate inputs with sparse, counted local pattern features.

NNUE v1 uses a fixed 12x12 whole-board one-hot input. That is acceptable for
the current 12x12 bot experiments, but it cannot generalize to Virus variants
with different board sizes. NNUE v2 uses local topological patterns, plus a
small dense manual feature side channel, so the same model can evaluate boards
with different dimensions.

## Prototype Scope

The v2 prototype is deliberately float-first:

- Implement and train the reference model in Python/PyTorch.
- Use standard floating-point math and ReLU activations.
- Do not implement int8/int16 quantization, SIMD, VNNI, or C++ inference in
  the first prototype.
- Use only 5x5 local patterns at first.
- Use WDL outcome labels from the side-to-move perspective.

Quantized Java/C++ inference is a later optimization after the pattern model
proves that it can learn useful play.

## Feature Space

The sparse input feature universe is a promoted dictionary of 5x5 pattern
signatures. A feature means:

> Pattern ID N occurs at this local board window and distance bucket.

Features are not tied to absolute `(x, y)` coordinates. They are permanent
inference features, not temporary training-only virtual features. They must not
be coalesced or smeared back into singleton cell weights, because that would
destroy the logical conjunction represented by the pattern.

### Pattern Alphabet

Each cell in a 5x5 pattern is encoded using the perspective-normalized game
state:

- `EMPTY`
- `NEUTRAL`
- `BASE`
- `NORMAL_SELF`
- `NORMAL_OPPONENT`
- `FORTIFIED_SELF`
- `FORTIFIED_OPPONENT`
- `OUT_OF_BOUNDS`

`OUT_OF_BOUNDS` is used for windows that touch a board edge.

### Pattern Emission Rule

Do not emit all-empty background windows. A 5x5 pattern is emitted only when it
contains at least one active entity:

- `NORMAL_SELF` or `NORMAL_OPPONENT`
- `FORTIFIED_SELF` or `FORTIFIED_OPPONENT`
- `NEUTRAL`
- `BASE`

`EMPTY` cells are still allowed inside an emitted pattern. They define shape,
boundary, and local liberties. An all-empty 5x5 window is the implicit zero
state and is skipped.

### Distance Bucket

Because v2 abandons absolute coordinates, each 5x5 signature includes a
scale-invariant global context bucket:

- Compute Manhattan distance from the pattern center cell to the enemy base.
- Normalize by the maximum possible Manhattan distance on the current board:
  `(rows - 1) + (cols - 1)`.
- Bucket the normalized value into a small fixed set, initially 3 to 5 buckets
  such as `NEAR`, `MID`, and `FAR`.

The exact bucket count is a tuning parameter, but the prototype should start
small.

## Perspective Handling

Use the Stockfish-style two-accumulator structure:

- Build one accumulator from the side-to-move perspective (`STM`).
- Build one accumulator from the not-side-to-move perspective (`NSTM`).
- Canonicalize ownership as `SELF` and `OPPONENT` for each perspective.
- Concatenate the two accumulator outputs as `[STM, NSTM]` before the dense
  layers.

For the current diagonal-base 1v1 setup, the opponent perspective should use
the appropriate player-perspective transform, such as 180-degree rotation or
diagonal transpose, then ownership canonicalization. Do not deduplicate 90
degree rotations or arbitrary mirrors in the v2 prototype; directional advance
toward bases makes those patterns strategically different unless proven
otherwise.

## Occurrence Counts

Pattern inputs are counted occurrences, not booleans.

If Pattern #45 appears in three local windows, the model receives three
additions of Pattern #45's first-layer weight column. If one occurrence is
broken by a move, subtract that column once. Do not normalize counts for board
size. Larger boards naturally produce larger raw sums, and the model should
learn that scale.

Unseen patterns that are not in the promoted dictionary are ignored at
inference time.

## Dictionary Generation

During self-play/data generation:

1. Scan board positions for emitted 5x5 pattern signatures.
2. Track approximate frequencies with a Counting Bloom Filter as a memory
   efficient pre-filter.
3. After data generation, promote patterns above the chosen frequency threshold
   into an exact hardcoded/exported dictionary.
4. Target an initial dictionary size of roughly 2,000 to 10,000 promoted
   patterns.

The Counting Bloom Filter is not the final dictionary. It only selects likely
frequent candidates. Promoted inference features must live in an exact mapping
from canonical pattern signature to feature ID.

For the v2 prototype, ignore theoretical pruning rules such as "remove smaller
patterns contained in larger patterns." Use only frequency thresholding and the
explicit perspective canonicalization rules above.

## Incremental Accumulator

The runtime accumulator stores the first-layer pre-activation sums for pattern
features.

When a move changes one or more cells:

1. Identify the union of all 5x5 windows overlapping the changed cells. A
   single changed cell affects up to 25 windows.
2. For each affected window in the old board, compute its dictionary ID. If it
   is present, subtract that pattern weight column once.
3. For the same affected window in the new board, compute its dictionary ID. If
   it is present, add that pattern weight column once.
4. Process the union of affected windows so multi-cell moves do not double
   subtract or double add the same window.

Manual dense features are handled separately. Recalculate them from scratch
per position or per move and concatenate them after the sparse accumulator
output.

## Manual Dense Features

NNUE v2 appends the 14 recent manual features as a separate dense side input.
Those features are not documented in this repository yet; the exact list or
source file must be supplied before implementation.

Do not use the older 26-per-player / 104-feature Go-style feature plan as the
v2 source of truth.

## Training Target

Train the prototype on WDL outcomes from the side-to-move perspective:

- `Win = 1.0`
- `Draw = 0.5`
- `Loss = 0.0`

Use MSE for the first prototype. This intentionally differs from the v1 memory
that outcome labels were noisy for fixed-board training. For v2, WDL is the
starting target because the goal is to prove the representation and extractor
before adding teacher/search/bootstrap targets.

## Initial Network Shape

The intended inference shape is:

```text
sparse pattern accumulator STM:  1024
sparse pattern accumulator NSTM: 1024
manual dense side input:         14

concat -> dense hidden -> dense hidden -> scalar WDL/value
```

A concrete starting point is:

```text
[1024 STM + 1024 NSTM + 14 manual] -> 16 -> 32 -> 1
```

The exact accumulator width is tunable. Keep the first prototype small enough
to iterate quickly.

## Branching And Delegation

All NNUE v2 implementation PRs should target the `v2` branch, not `master`.
The `master` branch remains available for NNUE v1 and other production-path
work until v2 has evidence that it should be promoted.

Delegated work should state its merge target explicitly:

```text
Merge target: v2 branch, not master.
```
