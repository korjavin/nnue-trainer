# Gemini Context & Session Memory

This file preserves conversation context and orchestrator state across sessions.

## 🛠️ Role and Capabilities
- **Role**: Orchestrator / Architect (following the `/architect` and `/develop` skills, adapted for local subagents and Jules).
- **Core Loop**:
  1. Understand requirements, research code, root-cause.
  2. File tasks into **Beads** (`bd create`).
  3. Delegate implementation:
     - **No Ralphex without user confirmation**: Do not run ralphex unless explicitly approved.
     - **High-Priority / Blocker Tasks**: Delegate to **localsubagents** (using `invoke_subagent` running developer model).
     - **Parallel / Non-Blocking Tasks**: Author plans under `docs/plans/` and delegate to **Jules** (via `jules-task-from-plan` skill or `jules-api` skill for REST API operations).
       - *Note on Jules*: Cheap/free (100 runs/day), parallelizable (up to 10 concurrent), but slow and junior-level quality.
       - *Double-Attempt*: For complex tasks, start two parallel Jules sessions and pick the best solution.
       - *Fail-Fast*: If Jules goes down the wrong path or gets stuck, abort and restart/redelegate immediately instead of fixing it.
       - *Feedback Loop*: Comment directly on the GitHub PR or use `jules-api` `:sendMessage` / `:approvePlan` to steer active sessions; Jules updates code automatically.
  4. Review and merge PRs (plain merge commits only), close beads, sync Dolt.
     - **Watch for workarounds**: Audit PR diffs to ensure Jules is not bypassing test failures or compilation bugs by changing build files, build tool settings, or disabling tests. Request clean code fixes via PR comments.
  5. Report active task and sync status.

## 📈 Active Status (July 21, 2026)
- **Active Jules Sessions**:
  * **Plan 30 (`nnue-trainer-ntd.7` - TD-bootstrapped value labels)**:
    - Session #1: `8275694817017901931`
    - Session #2: `10332515160927540011`
  * **Plan 31 (`nnue-trainer-ntd.5.4` - Java self-play distribution shift)**:
    - Session #1: `1004752041680703003`
    - Session #2: `1145759515787579085`
  * **Plan 32 (`nnue-trainer-5gp` - Web 1v1 win/loss banner UX on `virusgame`)**:
    - Session #1: `3372291654182000087`
    - Session #2: `1197100044723953267`
  * **Plan 33 (`nnue-trainer-ntd.3` - Java vs Python NNUE forward parity)**:
    - Session #1: `13947747171501047668`
    - Session #2: `4176105637494756334`
  * **Plan 34 (`nnue-trainer-d4a.3.1` - NNUE v2 5x5 pattern signature contract -> PR target: `v2` branch)**:
    - Session #1: `378978716257848304`
    - Session #2: `6377152651848192615`
  * **Plan 35 (`nnue-trainer-d4a.2.2` - NNUE v2 14 dense manual features -> PR target: `v2` branch)**:
    - Session #1: `16637465991021918791`
    - Session #2: `4078645704865394641`
  * **Plan 36 (`nnue-trainer-d4a.1.1` - NNUE v2 reference pattern accumulator -> PR target: `v2` branch)**:
    - Session #1: `18098032761261541976`
    - Session #2: `10477024322811316008`
  * **Plan 37 (`nnue-trainer-raz.2.3.1` - P0 TT fix & negamax refactor -> PR target: `master`)**:
    - Session #1: `9357723369232559264`
    - Session #2: `5196517528715312877`
- **NNUE v2 Branch**: Created and pushed `v2` branch to origin for all NNUE v2 PRs.
- **Completed Task `nnue-trainer-raz.2.2`**: Successfully integrated full search upgrades (Transposition Table with Zobrist hashing, Principal Variation Search with null-window re-searches, move ordering with killer moves & history heuristics, depth-limited quiescence search, and custom-model gated opening book) via PR #38. All 65 unit tests pass cleanly in GitHub Actions CI. Closed bead `nnue-trainer-raz.2.2`.
- **Portainer CI/CD Deployment**: Added `Dockerfile`, `docker-compose.yml`, and `.github/workflows/deploy.yml` with secure non-root user setup, automatic image builds, tags, deploy branch pushes, and Portainer webhooks.
- **Gated challenging**: Gated outgoing challenges behind `CHALLENGER_MODE=true` environment variable to prevent automated spamming by default.

## ⚠️ Important Rules
- **No container recreation** without permission; always preview commands.
- **Context saving**: Keep this `GEMINI.md` file updated.
- Use `bd` for all task tracking.
