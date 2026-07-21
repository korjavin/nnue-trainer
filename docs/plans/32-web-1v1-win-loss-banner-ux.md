# Implementation Plan - 1v1 Game Win/Loss Outcome UX Banner (nnue-trainer-5gp)

## Issue
`nnue-trainer-5gp`: UX: Display explicit win/loss outcome banner when 1v1 game finishes.

## Problem Context
When a 1v1 multiplayer game finishes in `virusgame`, the web client game board and status header do not render a prominent, user-friendly outcome banner indicating whether Player 1 or Player 2 won, lost, or drew. Players must infer the result from piece counts or connection messages.

## Design Goals
1. Add an overlay/banner component in `multiplayer.js` / HTML UI when game state transitions to `FINISHED` or `GAME_OVER`.
2. Display explicit winner name/prefix ("Player 1 (Victory!)" vs "Player 2 (Defeated)") with distinct color styling (green for win, red/crimson for loss, gold for draw).
3. Provide a "Play Again / Rematch" button inside the end-game modal/banner.

## Step-by-Step Implementation

### Step 1: Frontend Game Status Overlay (`multiplayer.js`)
- In `handleGameFinished(payload)` or status update callback:
  - Determine local player ID vs winning player ID.
  - Render a responsive modal banner `#game-over-banner` showing:
    - Victory / Defeat title.
    - Final score / piece count breakdown.
    - Rematch button triggering a new challenge.

### Step 2: Styling
- Add smooth entrance CSS animations (`fade-in`, `scale-up`) and dark-mode backdrop blur.

## Verification
- Test in browser / mock WebSocket events.
