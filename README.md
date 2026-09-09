# FRC 1884 — Season 2027

Season2027 is the clean rollover of Team 1884's Season2026 robot repository with the Robotics Agentic Development Harness built in before student mechanism work begins.

The reusable project/tooling structure has been carried forward and renamed to `frc2027`; completed Season2026 mechanisms, subsystems, mechanism commands, autos, and game-specific field assets are intentionally removed.

> **Temporary toolchain baseline:** WPILib/vendor 2027 releases are not available yet. The repository uses the current 2026-compatible GradleRIO/vendor baseline until an official 2027 infrastructure-upgrade PR replaces it.

## Repository baseline

- mechanism-free `Robot` / `RobotContainer` shell
- reusable Season2026 OI and generic utility patterns under `org.Griffins1884.frc2027`
- Team 1884 WPILib / Gradle tooling baseline
- in-repository Robotics Agentic Development Harness under `.github/robotics-harness/`
- shared `AGENTS.md` with thin Codex and Claude Code entry points
- diff-grounded learning verification policy with an authenticated mentor-only learning override
- risk classification and human approval policy
- exact-head Code Owner self-acceptance policy for author-owned PRs
- Monitoring v1 contract and sample mentor digest
- PR template with risk, safety, learning, testing, and rollback evidence
- CODEOWNERS for mentors, Software Lead, three sub-leads, and approved alumni
- CI job `build-and-format`
- safety-critical constants isolated under protected paths
- AGENTS.md stress-test findings

See [`docs/SEASON_ROLLOVER.md`](docs/SEASON_ROLLOVER.md) for the season rollover.

## Branch model

```text
task/*
  -> vision-localisation-lead | core-mechanisms-lead | autonomous-lead
  -> software-leads
  -> main
```

The five long-lived integration branches are:

- `vision-localisation-lead`
- `core-mechanisms-lead`
- `autonomous-lead`
- `software-leads`
- `main`

The desired hosted protection policy is documented in [`docs/BRANCH_PROTECTION_SETUP.md`](docs/BRANCH_PROTECTION_SETUP.md).

## Student and reviewer entry points

Start a robotics task in either Codex or Claude Code with the intended outcome.
The agent classifies scope, responds visibly, inspects read-only and completes a
clarification checkpoint. It presents a plan and waits for your explicit yes
before edits, task branches or write-producing commands. Small changes use a
short plan. After validation and complete uncommitted-diff review, answer the
learning questions in your own words; the agent evaluates them before commit,
push and a template-based PR. The existing verified mentor-learning exception
retains its narrow limits. See the [shared workflow](.github/robotics-harness/WORKFLOW.md).

A reviewer starts a fresh context and asks:

```text
review PR #123
review PR FRC1884/Season2027#123
```

Explicit skill entries are `$agentic-review` in Codex and `/agentic-review` in
Claude Code. Built-in `/review` is not this harness. Both routes load the same
trusted-base [review policy](.github/robotics-harness/REVIEW_POLICY.md) and
[review template](.github/robotics-harness/REVIEW_TEMPLATE.md). The reviewer
verifies the gh human account, agrees a short review plan, inspects the complete
diff and shows the exact report for separate publication approval. Publication
is an explicit-SHA COMMENT review followed by read-back. It never counts as a
human approving review and does not merge or deploy.

Codex uses `.agents/skills/`; Claude uses `.claude/skills/` and root `CLAUDE.md`
imports `@AGENTS.md`. `.codex/skills/` retains compatibility references. Read
applicable nested instructions explicitly and revalidate approvals on resume.
Fixture results do not prove client compatibility: real loading from root and
subdirectory, fresh and resumed sessions must have separate evidence. Missing
execution/authentication is NOT RUN/BLOCKED. Local guards and editable state are
not tamper-proof; direct shell/API operations remain outside their enforcement.

The native SessionStart message provides the actual session ID and reserved input/report directory. Follow the shared WORKFLOW command section: author commands take `--root --session --provider`; review commands take `--root --session-id --provider`. The legacy `.codex` skill is `agentic-review-compat` with implicit invocation disabled. A hook firing is not evidence of model instruction loading.
