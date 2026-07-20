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
  4. Review and merge PRs (plain merge commits only), close beads, sync Dolt.
  5. Report status table.

## 📋 Status Table

| Bead ID | Track | PR | State | Note |
|---|---|---|---|---|
| **nnue-trainer-8ka** | WebSocket Connection & Game Protocol | — | open | Epic (P1) |
| **nnue-trainer-a22** | Game Board Representation & Rule Validation | — | open | Epic (P1) |
| **nnue-trainer-a0c** | NNUE Network Representation | — | open | Epic (P1) |
| **nnue-trainer-2mt** | Alpha-Beta Search Engine | — | open | Epic (P2) |
| **nnue-trainer-ntd** | Training Pipeline | — | open | Epic (P2) |
| **nnue-trainer-raz** | Tournament & Performance Verification | — | open | Epic (P2) |

*(Detailed task hierarchy is tracked inside `bd`; run `bd list` to view full backlog).*

## ⚠️ Important Rules
- **No container recreation** without permission; always preview commands.
- **Context saving**: Keep this `GEMINI.md` file updated.
- Use `bd` for all task tracking.
