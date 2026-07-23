#!/usr/bin/env bash
# One TD-leaf pass THROUGH THE STRONG GoBot search (Phase 2): self-play where move
# selection AND the TD-leaf target come from GoBotSearcher with the NNUE leaf, then
# train.py -> nnue_weights.json. This is the negamax td_leaf_pass.sh with the GoBot
# self-play knobs pre-set; it just delegates so the two paths can't drift.
#
# GoBot-specific knobs (env):
#   GOBOT_NODE_LIMIT=60000   # node budget per move (the proven strong live setting)
#   GOBOT_FIXED_DEPTH=0      # >0 uses a fixed depth instead of the node budget
#   EPSILON=0.1 EXPLORE_TURNS=6   # diversity (raise for more distinct games)
# Shared knobs (passed straight through to td_leaf_pass.sh / SelfPlayGenerator):
#   NUM_GAMES  TD_LAMBDA  SEED  MAX_TURNS  OUT   (SEARCH_DEPTH is unused in GoBot mode)
set -euo pipefail
cd "$(dirname "$0")"

# SelfPlayGenerator reads these from the environment; export so the java subprocess
# td_leaf_pass.sh launches inherits them (it does not clobber vars it doesn't own).
export SELFPLAY_SEARCH=GOBOT
export GOBOT_NODE_LIMIT="${GOBOT_NODE_LIMIT:-60000}"
export GOBOT_FIXED_DEPTH="${GOBOT_FIXED_DEPTH:-0}"
export EPSILON="${EPSILON:-0.1}"
export EXPLORE_TURNS="${EXPLORE_TURNS:-6}"

echo ">> GoBot-search self-play: node_limit=$GOBOT_NODE_LIMIT fixed_depth=$GOBOT_FIXED_DEPTH eps=$EPSILON explore_turns=$EXPLORE_TURNS"
exec ./td_leaf_pass.sh
