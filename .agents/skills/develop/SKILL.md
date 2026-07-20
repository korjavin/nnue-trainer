---
name: develop
description: Autonomously work bd (beads) issues end-to-end at a chosen concurrency — claim, plan (with a critical clarify loop), run ralphex in an isolated worktree, open a PR, drive CI green, hand off for review, and close the bead on merge. Trigger when the user runs "/develop", "/develop N", "/develop N <epic-id>", or asks to "work the backlog", "grind through ready issues", or "develop the epic".
---

# /develop — orchestrate ralphex over the bd backlog

Run ready bd issues through the full pipeline at concurrency `N`, optionally scoped to one epic. Requires `ralphex`, `bd` (beads), and `gh` in the project.

**Invocation:** `/develop [N] [epic-id]`
- `N` — how many ralphex runs may execute at once. Omitted → `1`.
- `epic-id` — optional; if present, only work issues under that epic (its children). Omitted → any ready issue.

## The one hard constraint (read first)

**You (the main agent) are the orchestrator. Subagents cannot ask the user questions** — `AskUserQuestion` only works in the main loop. So this is NOT "spawn N subagents that each do everything." It is:

- **Interactive steps run in the main loop, serialized through the user:** plan-clarification, the "PR ready" ping, and the review handoff. The user can only answer one thing at a time, so serializing these costs nothing.
- **`N` bounds the autonomous, hours-long part:** the background `ralphex` runs. That is the only thing worth parallelizing.

You keep a live pool of up to `N` background ralphex processes and refill a slot the moment one frees up. Track every in-flight task's state in your narration (a small status table), not in a file.

## Preflight

```bash
which ralphex && which gh && which bd    # all three required; stop and report if any missing
gh auth status                            # PR + CI steps need this
```

## Step 1 — build the work queue

```bash
bd ready
```

Filter to workable issues:
- **Exclude epics themselves** (`[epic]` rows — type `epic`). Work their children, not the container.
- **If `epic-id` was given**, keep only issues whose parent chain includes it. Use `bd show <epic-id>` to see children, or filter `bd ready` output by the `← <Epic title>` suffix.
- Skip anything already `in_progress` by someone else.

This queue is your backlog. Process it pipelined — do not plan everything up front.

## Step 2 — per-slot pipeline

Repeat until the queue is empty AND no ralphex run is in flight. Whenever a slot is free (fewer than `N` ralphex runs active) and the queue is non-empty, fill it:

### 2a. Claim
Pick the next queued issue and claim it immediately so no parallel run takes it:
```bash
bd update <id> --claim
```
Read it fully: `bd show <id>`.

### 2b. Triage — is this even a ralphex job?
Before planning, read the issue and the code it touches and decide:

Two conditions decide it — ralphex is for work that is **(a) more than a simple fix AND (b) already researched and understood** (you know *what* to build; the approach and acceptance are clear):

- **Genuinely trivial / one-touch fix** (a single-line change, a copy tweak, a wrong-condition guard, deleting a dead branch — the *whole* fix fits in your head and touches one place): **skip ralphex, fix it directly**, run the relevant test/build, commit on a branch, open a PR, hand off at 2g. The plan + iteration machinery is pure overhead for a change you could type in a minute.
- **Non-trivial work you can form a concrete plan for** (a real feature or multi-file change — whether or not anyone pre-researched it): **this is what ralphex is for — go through the full flow, 2c onward.** You do NOT need the work to have been researched *for* you: the `ralphex-plan` skill (2c) does the context discovery itself, so if you're handed a non-trivial task that wasn't pre-scoped by a planner, **read the code, run ralphex-plan to research + author the plan, and run ralphex yourself.** Its task→review→external-review→finalize loop produces materially better code than a one-pass edit, and it's worth the extra tokens. Don't sit waiting for someone else to hand you a spec — if you can write the plan, ralphex it.
- **Genuinely can't form a plan yet — open-ended debugging with an unknown root cause, or multiple plausible approaches you haven't resolved**: ralphex is not the tool *yet*, because it executes a plan it doesn't have. Do the root-cause / research first (that's ordinary investigation, not ralphex), and the moment it resolves into concrete, plannable work, it becomes a ralphex job. The bar is "can I write a real plan for this?", not "did someone hand me one?"

So: ralphex is neither "for everything" nor "rarely" — it's for **non-trivial work you can plan** (researching it yourself via ralphex-plan counts). Don't ralphex a one-liner (overhead); don't ralphex a genuinely-unknown root cause (no plan exists yet); do ralphex any researched-or-researchable feature/fix.

### 2c. Plan — use the ralphex-plan skill (do NOT hand-write plans)
For anything not handled directly in 2b, **invoke the `ralphex-plan` skill** to author `docs/plans/YYYYMMDD-<slug>.md`. Do not write the plan file yourself — the skill produces the exact structure ralphex requires (context discovery, `### Task N:` sections, progress-tracking block). Hand-written plans routinely fail ralphex's validator (see gotchas).

**Critical judgment (this is the point of the step):**
- If the issue is **clear and self-contained**, let the skill produce the plan and proceed — do NOT interrogate the user for the sake of it.
- If the issue is **genuinely ambiguous** (unclear acceptance criteria, multiple plausible approaches with real trade-offs, missing decisions only the user can make), STOP and ask the user with `AskUserQuestion`, **one question at a time**, and loop until the plan is solid. Never invent requirements to avoid asking, and never ask about things you can decide yourself (naming, test strategy — no unit tests per ralphex-plan policy).
- If an issue turns out to be underspecified to the point of "I can't responsibly plan this," say so, leave it claimed-but-unstarted or release it (`bd update <id> --status=open`), flag it (`bd human <id>`), and move to the next queue item rather than burning a slot on guesswork.

Commit the plan file so ralphex's worktree (branched from the base branch) includes it — **and commit or stash everything else so the tree is clean apart from the plan** (see gotchas: ralphex refuses to create a worktree if any non-plan file is dirty):
```bash
git add .beads/ && git commit -m "chore: bd export"       # claim churn, if any
git add docs/plans/YYYYMMDD-<slug>.md && git commit -m "plan: <id> <short title>"
git status --short                                          # MUST be clean now
```
(No push to the base branch — local commit only, so the ralphex worktree sees the plan.)

### 2d. Launch ralphex (background)
```bash
ralphex --worktree --max-iterations 25 docs/plans/YYYYMMDD-<slug>.md
```
Run with `run_in_background: true`. **Record the task_id and the progress file** `.ralphex/progress/progress-YYYYMMDD-<slug>.txt`. Default mode is Full (task + Claude review + external review + finalize) — do not change it unless the user asked. **If it exits within seconds, it did not run — it hit one of the launch gotchas below; fix and relaunch, don't count it as a real attempt.**

**`--worktree` is OPTIONAL.** It makes ralphex create its *own* isolated worktree off `master` — which is why it requires launching from `master` HEAD (see gotchas). **When you are ALREADY in an isolated working copy — e.g. you are a subagent running inside your own git worktree, or the orchestrator handed you an isolated checkout — drop `--worktree` and run ralphex IN-PLACE on your current branch:**
```bash
ralphex --max-iterations 25 docs/plans/YYYYMMDD-<slug>.md
```
In-place mode has **no master requirement** (it operates on whatever branch you're on) and still runs the full task→review→external-review→finalize pipeline. It only needs a clean tree apart from the plan file (same as `--worktree`). This is the mode to use whenever `--worktree` would refuse because you're not on master — do NOT fall back to a hand-written direct fix just because `--worktree` won't launch; use in-place ralphex and keep the quality pipeline. Commit + open the PR from your current branch afterwards (2f).

### 2e. Monitor; restart on failure
Watch via `TaskOutput` (block:false) and `tail` the progress file.
- **Exit 0** → success, go to 2f.
- **Non-zero exit / hang** → inspect the progress-file tail for the cause. If it's a transient failure (rate limit, executor crash, idle timeout), **restart ralphex on the same plan** (it resumes from plan checkboxes). Cap at **2 restarts**. If it still fails, stop that task, report the failure reason to the user, release the bead (`bd update <id> --status=open`), free the slot.

### 2f. Open the PR
ralphex works on branch `<derived>` in `.ralphex/worktrees/<derived>`. Push and open a **draft** PR:
```bash
git -C .ralphex/worktrees/<derived> push -u origin <derived>
gh pr create --draft --head <derived> --title "<id>: <title>" \
  --body "Closes bd <id>.\n\nPlan: docs/plans/YYYYMMDD-<slug>.md\n\n<ralphex summary>"
```
(If ralphex's finalize step already pushed/opened a PR, reuse it instead of duplicating.)

**Do NOT re-run the full test suite locally for ralphex/codex-produced branches.** ralphex's own acceptance-criteria (its Verify task) already runs `go build` + `go test` + `pnpm test` as part of the run, and CI re-runs them on the PR — a third local pass (`go test ./... -race`, `pnpm test`) is wasted minutes. Go straight from ralphex-success to push + PR + CI. Fast, cheap, project-specific sanity checks that ralphex's suite may NOT cover are still worth a glance (e.g. migration-number contiguity, the `go list -deps` import-boundary landmine, a new `window.*` global allowlist) — but not the suites themselves. (This exception is only for agent-produced branches; for your own **direct fixes** in 2b, still verify locally before pushing — there's no ralphex Verify step there.)

### 2g. Drive CI green (do NOT ping the user on red)
```bash
gh pr checks <pr> --watch
```
- **Green** → 2h.
- **Red** → diagnose from `gh pr checks` + failing job logs (`gh run view <run> --log-failed`). Fix in the ralphex worktree — a targeted commit, or `ralphex --review` on the same plan for a broader pass. Re-push, re-watch. Cap at **2 fix passes**. If still red, THEN tell the user, with the concrete failure and what you tried — this is the one case where you surface red CI.

### 2h. Hand off — or self-merge a trivial, necessary fix
Default: mark the PR ready (`gh pr ready <pr>`) and notify the user: "PR #<pr> for bd `<id>` is ready — CI green." If running detached, use `PushNotification`. Then **leave this task in a `review` state and free the slot** — keep filling other slots and monitoring.

**Exception — self-merge without asking** when ALL hold: (a) the change is small and low-risk (a flaky-test fix, an obvious one-line/one-file bug fix, a config/copy correction — the kind of thing you'd fix inline), (b) it is clearly necessary (e.g. unblocks CI, fixes a P0/P1 regression, or removes a recurring failure — like the flaky-relay-test fix `#432`), and (c) CI is fully green. In that case merge it with the project's convention (`gh pr merge <pr> --merge` here — never `--squash`/`--rebase`), then go straight to 2i (close the bead). Prefer merging such CI-unblockers *first* so later PRs stop needing reruns. Still hand off (don't self-merge) anything feature-sized, architecturally significant, or where you're unsure — those are the user's call.

### 2i. On merge → close the bead
When the user says a PR is merged (or you detect `gh pr view <pr> --json state` = MERGED):
```bash
bd close <id> --reason="Merged in #<pr>"
```
ralphex already moved the plan to `docs/plans/completed/`. Refill the freed slot from the queue.

## Review handoff (interleaved)

After 2h a task waits on the user. When the user responds about a PR — "merge it", "change X", "close it" — act on that specific PR: merge it using the project's merge convention (check CLAUDE.md/AGENTS.md — some repos require plain merge commits and forbid squash/rebase), then run 2h; or feed requested changes back through `ralphex --review` / a targeted commit on that worktree, re-run 2f, re-hand-off. Other slots keep running while you handle review.

## Status reporting

Each turn, show a compact table of in-flight tasks so the user always knows the pool state:

```
bd id      state        pr    note
abc-xxx    running      —     ralphex task iter 12/25
abc-yyy    review       #431  CI green, awaiting your review
abc-zzz    ci-fixing    #430  1/2 fix passes, lint failing
```

## ralphex + worktree gotchas (hard-won)

`ralphex --worktree` fails fast (exits in seconds) on any of these. A quick exit is never a real run — diagnose and relaunch; it doesn't count against the 2-restart cap.

- **Clean tree required, except the plan file.** ralphex refuses with `cannot create worktree: worktree has uncommitted changes other than the plan file` if *any* other file is dirty. The usual culprit is `.beads/*.jsonl` churn from `bd update --claim` (bd rewrites the export on nearly every command, so it can re-dirty after you commit — commit it again right before launching). Also watch for pre-existing uncommitted work you didn't create: **do not revert it** (it may be someone's in-progress work — this repo had a live `docs/cloud-mode.md` edit); instead `git stash push -- <that file>` to get a clean tree, launch, then `git stash pop`, or just switch that issue to a direct fix.
- **Plan file MUST use `### Task N:` (or `### Iteration N:`) headings.** A plan with only a `## Tasks` list of `- [ ]` checkboxes fails validation: `no executable task sections`. This is exactly why 2c uses the **ralphex-plan skill** — it emits the right structure. If you ever hand-edit a plan, keep the `### Task N:` headers.
- **Launch from `master`.** ralphex `--worktree` refuses with `worktree creation requires master branch, currently on "<x>"` unless HEAD is `master`. So the plan must be committed **on local master**, and you must `git checkout master` before launching. Typical setup: author the plan on a scratch branch off `origin/master`, then `git branch -f master origin/master` (drops stale local master commits — safe if they're already upstream/regenerable; a ref move, not `reset --hard`, which the sandbox blocks), `git checkout master`, `git cherry-pick <plan-commit>`, confirm a clean tree, launch. ralphex branches its own worktree off master; your local master stays local (never push it).
- **A stale branch poisons the plan.** If a prior launch created the branch `<slug>` before you fixed the plan, ralphex reuses *that branch's* committed plan version, not your updated one on the base branch — so it keeps failing on the old content. Delete it first: `git worktree prune && git branch -D <slug>`, then relaunch so it recreates the branch from current base.
- **The branch/worktree name is the plan's slug**, e.g. plan `docs/plans/20260706-foo.md` → branch `foo` (date prefix stripped) in `.ralphex/worktrees/foo/`. Use that path for the 2f push.

## Guardrails

- **Never push to the base branch or force-push without the user's say-so.** Merge only when the user approves a specific PR — the one standing exception is a trivial, necessary, CI-green fix (see 2h: flaky-test fixes, obvious one-file bug fixes, CI unblockers), which you may self-merge. Anything feature-sized or uncertain still waits for the user. Always follow the project's merge convention.
- **Claim before planning** so parallel runs never collide on the same bead.
- **Do bd operations from the main checkout**, never inside a ralphex worktree (they share the git dir but not the working tree).
- **Respect the project's rules** (read CLAUDE.md / AGENTS.md at the start of a run) — ralphex runs inside the repo and its review passes enforce them, but call it out if a plan would violate one.
- If `N` runs would exceed sane local resources, cap it and say so.
