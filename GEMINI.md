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
  5. Report status table.

## 📋 Status Table

| Bead ID | Track / Plan | Jules Session ID | State | Note |
|---|---|---|---|---|
| **nnue-trainer-8ka** | WebSocket Connection & Game Protocol | — | open | Epic (P1) |
| `nnue-trainer-8ka.1` | Protocol: Configure Maven Dependencies | `6495472952424571837` | Completed | Plan: `01-configure-dependencies.md` |
| `nnue-trainer-8ka.2` | Protocol: Implement Connection Manager | `9022648143242651257` | Planning | Plan: `06-websocket-client.md` |
| `nnue-trainer-8ka.3` | Protocol: Implement Handshake Handlers | `17955866970117918486` | Planning | Plan: `07-protocol-handshake.md` |
| `nnue-trainer-8ka.4` | Protocol: Implement Game Loop Messages | `8504532035093649533` | Planning | Plan: `08-game-loop-messages.md` |
| **nnue-trainer-a22** | Game Board Representation & Rule Validation | — | open | Epic (P1) |
| `nnue-trainer-a22.1` | Board: Implement Coordinates System | `3849428682639415005` | In Progress | Plan: `02-board-representation.md` |
| `nnue-trainer-a22.2` | Board: Implement Base Connection Search | `5764093050482660391` | Planning | Plan: `03-base-connection-search.md` |
| `nnue-trainer-a22.3` | Board: Implement Move Validator | `15869792185815581223` | Planning | Plan: `04-move-validator.md` |
| `nnue-trainer-a22.4` | Board: Implement Move Generator | `1922572738557334799` | Planning | Plan: `05-move-generator.md` |
| **nnue-trainer-a0c** | NNUE Network Representation | — | open | Epic (P1) |
| `nnue-trainer-a0c.1` | NNUE: Design Board Feature Mapping | `11144204111641442719` | Planning | Plan: `09-nnue-feature-mapping.md` |
| `nnue-trainer-a0c.3` | NNUE: Implement forward pass inference | `12738943261395940269` | Planning | Plan: `10-nnue-forward-pass.md` |
| **nnue-trainer-2mt** | Alpha-Beta Search Engine | — | open | Epic (P2) |
| **nnue-trainer-ntd** | Training Pipeline | — | open | Epic (P2) |
| **nnue-trainer-raz** | Tournament & Performance Verification | — | open | Epic (P2) |
| **nnue-trainer-c54** | Continuous Integration & Quality Gates | — | open | Epic (P1) |
| `nnue-trainer-c54.1` | CI: Configure initial GitHub Actions workflow | — | Completed | Pushed JDK 25 Maven build |
| `nnue-trainer-c54.2` | CI: Add Checkstyle / Spotless checks | — | open | P2 task |
| `nnue-trainer-c54.3` | CI: Add code coverage gates | — | open | P3 task |
| `nnue-trainer-9sb` | Docs: Create and fill README.md | — | open | P2 task |

*(Detailed task hierarchy is tracked inside `bd`; run `bd list` to view full backlog).*

## ⚠️ Important Rules
- **No container recreation** without permission; always preview commands.
- **Context saving**: Keep this `GEMINI.md` file updated.
- Use `bd` for all task tracking.
