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
  4. Review and merge PRs (plain merge commits only), close beads, sync Dolt.
  5. Report status table.

## 📋 Status Table
No tasks filed yet.

| Bead ID | Track | PR | State | Note |
|---|---|---|---|---|
| — | — | — | — | — |

## ⚠️ Important Rules
- **No container recreation** without permission; always preview commands.
- **Context saving**: Keep this `GEMINI.md` file updated.
- Use `bd` for all task tracking.
