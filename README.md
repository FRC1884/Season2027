# FRC 1884 — Season 2027

Season2027 is the clean rollover of Team 1884's Season2026 robot repository with the Robotics Agentic Development Harness built in before student mechanism work begins.

The reusable project/tooling structure has been carried forward and renamed to `frc2027`; completed Season2026 mechanisms, subsystems, mechanism commands, autos, and game-specific field assets are intentionally removed.

> **Temporary toolchain baseline:** WPILib/vendor 2027 releases are not available yet. The repository uses the current 2026-compatible GradleRIO/vendor baseline until an official 2027 infrastructure-upgrade PR replaces it.

## Repository baseline

- mechanism-free `Robot` / `RobotContainer` shell
- reusable Season2026 OI and generic utility patterns under `org.Griffins1884.frc2027`
- Team 1884 WPILib / Gradle tooling baseline
- in-repository Robotics Agentic Development Harness under `.github/robotics-harness/`
- `AGENTS.md` v1 for normal Software Team Member Codex sessions
- diff-grounded learning verification policy
- risk classification and human approval policy
- Monitoring v1 contract and sample mentor digest
- PR template with risk, safety, learning, testing, and rollback evidence
- CODEOWNERS for mentors, Software Lead, three sub-leads, and approved alumni
- CI job `build-and-format`
- safety-critical constants isolated under protected paths
- AGENTS.md stress-test findings

See [`docs/SEASON_ROLLOVER.md`](docs/SEASON_ROLLOVER.md) for the season rollover and [`docs/SCHOOL_ADMIN_DEMO.md`](docs/SCHOOL_ADMIN_DEMO.md) for the school-facing demo.

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

## Codex workflow

A normal coding Codex session reads `AGENTS.md` and the Harness Markdown policies, classifies the actual objective under the Robotics-Only Scope Policy, and denies unrelated work before task actions. An IN SCOPE request then follows:

**robotics scope PASS → clarification → plan → risk → implementation → build/test/format → learning verification → human approval where required → PR**

AI PR review is deliberately **manual/on-demand** rather than an automatic GitHub AI workflow.

A user opens/uses their own Codex session and asks:

```text
review PR #<number>
```

Codex then reads `.github/robotics-harness/REVIEW_POLICY.md` and `REVIEW_TEMPLATE.md`, inspects the exact current PR head, produces the structured Markdown review, and may publish the result to GitHub when access is available.

No `OPENAI_API_KEY` is required for this review model.

## School-admin demo

The demonstration is one small change through the governed loop, followed by a separate user-invoked Codex review and a sample monitoring digest. The final PR stays open and unmerged so the human authority boundary remains visible.
