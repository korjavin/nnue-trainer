# NNUE v3 runtime leaf

## NPS benchmark (Task 5)

`NNUEv3BenchmarkTest` (opt-in: `NNUEV3_BENCH=1 ./mvnw test -Dtest=NNUEv3BenchmarkTest`) searches the
8 real corpus boards of the parity fixture at the live 60k-node budget, once with
`LeafEval.HAND_TUNED` and once with `LeafEval.NNUEV3`. Both runs expand the same 300,000 nodes
(`chooseNodeBudget` stops at exactly the limit), so the wall-clock ratio is a straight NPS
comparison.

| leaf | nodes | wall | NPS |
| --- | --- | --- | --- |
| hand-tuned | 300,000 | 5,292 ms | 56,689 |
| NNUE v3 (full recompute) | 300,000 | 1,076 ms | 278,810 |

**Ratio: 4.9x faster than hand-tuned.** Single-eval throughput is ~1.26M evals/s (0.0008 ms/eval) on
a 12x12 board. Machine: AMD EPYC-Rome, Java 17.

**Is full recompute fast enough for the 60k-node budget? Yes, with a wide margin.** A full 60k-node
search costs ~215 ms with the v3 leaf versus ~1,060 ms with the hand-tuned eval. The v3 leaf is not
the bottleneck — it is cheaper than the eval it replaces, because 144 array reads and adds beat the
hand-tuned eval's per-position flood fills. Incremental accumulator updates would optimize the part
of the search that is already the fastest; they stay out of scope until a benchmark says otherwise.
