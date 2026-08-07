#!/usr/bin/env python3
"""Render the v3 position-aware features as 12x12 per-state weight heatmaps.

Reads (repo root):
  nnue_v3_weights.json       - v3.1 linear model: per-feature scalar weights
  nnue_v3_net.json           - H=32 net: w1[32][1152], w2[32] (row-major, feature-minor)
  nnue_v3_feature_stats.json - per-feature support (features never seen carry garbage
                               weights in the net, so we mask them out)

Writes docs/nnue-v3-heatmap.html - fully static, self-contained, theme-aware.

Feature id = (row*12 + col)*8 + state, states = PatternContract symbols 0..7.
"""
import json
import pathlib

import numpy as np

ROOT = pathlib.Path(__file__).resolve().parents[1]
OUT = ROOT / "docs" / "nnue-v3-heatmap.html"

STATE_NAMES = [
    "EMPTY", "NEUTRAL", "BASE_SELF", "BASE_OPPONENT",
    "NORMAL_SELF", "NORMAL_OPPONENT", "FORTIFIED_SELF", "FORTIFIED_OPPONENT",
]
# display order: material first, then bases, then the rest
DISPLAY = [4, 5, 6, 7, 2, 3, 1, 0]
STATE_DESC = {
    0: "cell is empty",
    1: "neutral (dead) cell",
    2: "the mover's base",
    3: "the opponent's base",
    4: "the mover's normal cell",
    5: "an opponent normal cell",
    6: "the mover's fortified cell",
    7: "an opponent fortified cell",
}


def load():
    w = json.loads((ROOT / "nnue_v3_weights.json").read_text())
    lin = np.zeros(1152)
    for k, v in w["weights"].items():
        lin[int(k)] = v

    n = json.loads((ROOT / "nnue_v3_net.json").read_text())
    w1 = np.array(n["w1"])          # [32][1152]
    w2 = np.array(n["w2"])          # [32]
    proj = (w2[:, None] * w1).sum(0)  # linearization: sum_h w2[h]*w1[h][f]

    stats = json.loads((ROOT / "nnue_v3_feature_stats.json").read_text())
    sup = np.zeros(1152)
    for f in stats["features"]:
        sup[(f["row"] * 12 + f["col"]) * 8 + f["state"]] = f.get("support", 0)

    return lin, proj, sup, w["meta"], n["meta"]


def cell_html(v, support, r, c, state, maxabs):
    corner = ' corner' if (r, c) in ((0, 0), (11, 11)) else ''
    if support <= 0:
        return f'<div class="cell none{corner}" title="r{r} c{c}: never seen in corpus"></div>'
    a = (abs(v) / maxabs) ** 0.7  # gamma so mid-size weights stay visible
    title = f"r{r} c{c} {STATE_NAMES[state]}: {v:+.0f} (support {support:.0f})"
    if a < 0.03:
        return f'<div class="cell{corner}" title="{title}"></div>'
    var = "--pos" if v > 0 else "--neg"
    return (f'<div class="cell{corner}" style="background:rgba(var({var}),{a:.2f})" '
            f'title="{title}"></div>')


def board(M, S, state, maxabs):
    cells = "".join(cell_html(M[r, c], S[r, c], r, c, state, maxabs)
                    for r in range(12) for c in range(12))
    seen = int((S > 0).sum())
    return (f'<div class="board-card"><h3>{STATE_NAMES[state]}</h3>'
            f'<p class="cap">{STATE_DESC[state]} &middot; {seen}/144 cells seen</p>'
            f'<div class="grid12">{cells}</div></div>')


def scale_bar(maxabs):
    sw = []
    for i in range(-6, 7):
        a = (abs(i) / 6) ** 0.7
        var = "--pos" if i > 0 else "--neg"
        bg = f'background:rgba(var({var}),{a:.2f})' if i else ''
        sw.append(f'<span class="s" style="{bg}"></span>')
    return (f'<div class="scale"><span class="lbl">&minus;{maxabs:.0f}</span>'
            f'{"".join(sw)}<span class="lbl">+{maxabs:.0f}</span>'
            f'<span class="lbl">(shared across the 8 maps below)</span></div>')


def boards_section(vec, sup, title, sub, anchor):
    M = vec.reshape(12, 12, 8)
    S = sup.reshape(12, 12, 8)
    mask = sup > 0
    maxabs = np.abs(vec[mask]).max()
    maps = "".join(board(M[:, :, s], S[:, :, s], s, maxabs) for s in DISPLAY)
    return (f'<h2 id="{anchor}">{title}</h2><p class="sub">{sub}</p>'
            f'{scale_bar(maxabs)}<div class="boards">{maps}</div>')


def tile(v, k, d=""):
    d = f'<div class="d">{d}</div>' if d else ''
    return f'<div class="tile"><div class="v">{v}</div><div class="k">{k}</div>{d}</div>'


def main():
    lin, proj, sup, wmeta, nmeta = load()
    mask = sup > 0
    corr = np.corrcoef(lin[mask], proj[mask])[0, 1]

    tiles = "".join([
        tile(f'{int(mask.sum())}/1152', "features seen", "in the replayed corpus"),
        tile(f'{wmeta["r2_holdout"]:.3f}', "linear R&sup2; holdout", "v3.1 ridge fit"),
        tile(f'{nmeta["top1_holdout"] * 100:.1f}%', "net top-1 holdout", "H=32 net"),
        tile(f'{corr:.2f}', "linear vs net corr", "on seen features, linearized"),
    ])

    linear_maps = boards_section(
        lin, sup, "The linear model, one weight per feature",
        "This <em>is</em> the model &mdash; score = bias + sum of the shown weights over the "
        "144 active features. Nothing is projected or approximated.", "linear")
    net_maps = boards_section(
        proj, sup, "The H=32 net, linearized",
        "The net is not linear, so these maps are a projection: for each feature, "
        "<code>&Sigma;<sub>h</sub> w2[h]&middot;w1[h][f]</code> &mdash; the feature's total "
        "first-layer contribution weighted by the output layer, ignoring the ReLU gate. "
        "A rough but honest summary of what the first layer attends to; expect it to be "
        "noisier than the linear model.", "net")

    html = f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>NNUE v3 &mdash; learned weights as 12&times;12 heatmaps</title>
<style>
  :root {{
    --bg: #fdfdfc; --fg: #1a1d21; --muted: #5c646e; --card: #f1f2f4;
    --accent: #2563eb; --border: #d8dce1; --cell: #e7e9ec;
    --pos: 37,99,235;   /* blue = good for the side to move */
    --neg: 185,28,28;   /* red  = bad for the side to move  */
  }}
  @media (prefers-color-scheme: dark) {{
    :root {{
      --bg: #14171a; --fg: #e6e8ea; --muted: #9aa3ad; --card: #1e2328;
      --accent: #60a5fa; --border: #30363d; --cell: #23282e;
      --pos: 96,165,250; --neg: 248,113,113;
    }}
  }}
  * {{ box-sizing: border-box; }}
  body {{ margin: 0; background: var(--bg); color: var(--fg);
    font: 16px/1.6 system-ui, -apple-system, "Segoe UI", sans-serif; }}
  main {{ max-width: 72rem; margin: 0 auto; padding: 2.5rem 1.25rem 4rem; }}
  h1 {{ font-size: 1.7rem; line-height: 1.25; margin: 0 0 .4rem; }}
  h2 {{ font-size: 1.3rem; margin: 2.6rem 0 .5rem; }}
  a {{ color: var(--accent); }}
  code {{ background: var(--card); border-radius: 4px; padding: .1em .35em;
    font-size: .88em; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }}
  .sub {{ color: var(--muted); margin: 0 0 1.2rem; max-width: 70ch; }}
  .note {{ background: var(--card); border-left: 4px solid var(--accent);
    padding: .8rem 1rem; border-radius: 6px; max-width: 70ch; }}
  .tiles {{ display: grid; grid-template-columns: repeat(auto-fill, minmax(11rem,1fr));
    gap: .7rem; margin: 1.2rem 0; }}
  .tile {{ background: var(--card); border: 1px solid var(--border);
    border-radius: 10px; padding: .7rem .9rem; }}
  .tile .v {{ font-size: 1.4rem; font-weight: 650; font-variant-numeric: tabular-nums; }}
  .tile .k {{ font-size: .75rem; color: var(--muted); text-transform: uppercase;
    letter-spacing: .04em; }}
  .tile .d {{ font-size: .8rem; color: var(--muted); }}
  .boards {{ display: grid; grid-template-columns: repeat(auto-fill, minmax(240px,1fr));
    gap: 1rem; }}
  .board-card {{ background: var(--card); border: 1px solid var(--border);
    border-radius: 12px; padding: .9rem; }}
  .board-card h3 {{ margin: 0; font-size: .95rem; }}
  .board-card .cap {{ margin: 0 0 .6rem; font-size: .78rem; color: var(--muted); }}
  .grid12 {{ display: grid; grid-template-columns: repeat(12, 1fr); gap: 1px;
    aspect-ratio: 1/1; }}
  .cell {{ border-radius: 2px; background: var(--cell); }}
  .cell.none {{ background: transparent; border: 1px dashed var(--border); }}
  .cell.corner {{ box-shadow: inset 0 0 0 1.5px var(--fg); }}
  .scale {{ display: flex; align-items: center; gap: 2px; margin: .6rem 0 1rem;
    flex-wrap: wrap; }}
  .scale .s {{ width: 24px; height: 14px; border-radius: 2px; background: var(--cell); }}
  .scale .lbl {{ font-size: .78rem; color: var(--muted); margin: 0 8px;
    font-variant-numeric: tabular-nums; }}
  ul {{ max-width: 72ch; }}
  li {{ margin-bottom: .5rem; }}
  footer {{ margin-top: 3rem; color: var(--muted); font-size: .9rem;
    border-top: 1px solid var(--border); padding-top: 1.2rem; }}
</style>
</head>
<body>
<main>
  <h1>v3 learned weights as 12&times;12 heatmaps</h1>
  <p class="sub">Did the model learn geometry a player would recognize? Every v3 feature is an
  absolute board cell plus a cell-state, so the whole model can be drawn: one 12&times;12 map per
  state, one colored square per learned weight. <a href="index.html">&larr; project home</a></p>

  <div class="note">
    <p style="margin:0 0 .5rem"><b>How to read these.</b> A feature is
    <code>(row, col, cell-state)</code> &mdash; e.g. &ldquo;cell (4,4) holds an opponent fortified
    cell&rdquo;. States are side-to-move relative (<code>SELF</code> = the mover), but coordinates
    are absolute: bases sit in the outlined corners (0,0) and (11,11), and either player may own
    either corner. <span style="color:rgb(var(--pos))"><b>Blue</b></span> = the feature raises the
    mover's evaluation, <span style="color:rgb(var(--neg))"><b>red</b></span> = it lowers it;
    intensity is magnitude on a scale shared across each model's 8 maps. Dashed cells were never
    seen in the training corpus (their weights are meaningless and are hidden). Hover any cell
    for the exact value. What to look for: does material near the base-to-base diagonal matter
    more than the edges? Are <code>SELF</code> and <code>OPPONENT</code> maps mirror images?</p>
  </div>

  <div class="tiles">{tiles}</div>

  {linear_maps}

  {net_maps}

  <h2>Observations from the actual values</h2>
  <ul>
    <li><b>The battle is on the diagonal, and the model knows it.</b> In the linear model the
    three-cell band around the (0,0)&ndash;(11,11) diagonal carries 4&ndash;5&times; the mean
    weight magnitude of the rest of the board (fortified-opponent: 1447 vs 295). The single
    strongest weight in the whole model is an opponent fortress at the center cell (4,4):
    &minus;5099 &mdash; an enemy fortress astride the corridor between the bases is the worst
    thing the model can see.</li>
    <li><b>It learned zero-sum symmetry without being told.</b> The <code>SELF</code> map of each
    material kind is close to the negated <code>OPPONENT</code> map at the same cells
    (correlation 0.82 for normal, 0.86 for fortified), and fortified weights outweigh normal
    ones. Nobody encoded &ldquo;my stone here is worth what your stone there costs me&rdquo;
    &mdash; it fell out of the data.</li>
    <li><b>The base maps are a confession, not geometry.</b> Base occupancy never changes, yet
    <code>BASE_SELF</code> learned +1049 at (0,0) and &minus;1049 at (11,11) (opponent exactly
    mirrored) &mdash; a &plusmn;2100-point bonus for merely being the player based in the (0,0)
    corner. That is a corpus asymmetry (who moves first / which openings were replayed), not
    board understanding, and it is a caution for reading the rest: some &ldquo;geometry&rdquo;
    may be corpus bias too.</li>
  </ul>

  <footer>
    Generated by <code>scripts/gen_v3_heatmap.py</code> from <code>nnue_v3_weights.json</code>,
    <code>nnue_v3_net.json</code> and <code>nnue_v3_feature_stats.json</code>.
  </footer>
</main>
</body>
</html>
"""
    OUT.write_text(html)
    print(f"wrote {OUT} ({OUT.stat().st_size/1024:.0f} KB)")


if __name__ == "__main__":
    main()
