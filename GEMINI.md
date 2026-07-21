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
     - **Parallel / Non-Blocking Tasks**: Author plans under `docs/plans/` and delegate to **Jules** (via the `jules-task-from-plan` skill).
       - *Note on Jules*: Cheap/free (100 runs/day), parallelizable (up to 10 concurrent), but slow and junior-level quality.
       - *Double-Attempt*: For complex tasks, start two parallel Jules sessions and pick the best solution.
       - *Fail-Fast*: If Jules goes down the wrong path or gets stuck, abort and restart/redelegate immediately instead of fixing it.
       - *Feedback Loop*: Comment directly on the GitHub PR to request changes/fixes; Jules listens to PR comments and updates the code automatically.
  4. Review and merge PRs (plain merge commits only), close beads, sync Dolt.
     - **Watch for workarounds**: Audit PR diffs to ensure Jules is not bypassing test failures or compilation bugs by changing build files, build tool settings, or disabling tests. Request clean code fixes via PR comments.
  5. Report active task and sync status.

## 📈 Active Status (July 21, 2026)
- **Active Jules Sessions**:
  * Session #1: `1924412228059294609` (Parallel implementation attempt for `nnue-trainer-ntd.5`)
  * Session #2: `6261202734603092814` (Parallel implementation attempt for `nnue-trainer-ntd.5`)
- **Closed Jules PRs**: All 8 previous open PRs from Jules (#23, #24, #25, #30, #31, #32, #33, #34) were safely closed because their code was already integrated in `master` and the respective beads were closed.
- **Portainer CI/CD Deployment**: Added `Dockerfile`, `docker-compose.yml`, and `.github/workflows/deploy.yml` with secure non-root user setup, automatic image builds, tags, deploy branch pushes, and Portainer webhooks.
- **Gated challenging**: Gated outgoing challenges behind `CHALLENGER_MODE=true` environment variable to prevent automated spamming by default.

## ⚠️ Important Rules
- **No container recreation** without permission; always preview commands.
- **Context saving**: Keep this `GEMINI.md` file updated.
- Use `bd` for all task tracking.
