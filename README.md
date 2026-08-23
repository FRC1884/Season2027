# FRC 1884 — Season 2027

Season2027 is the clean rollover of Team 1884's Season2026 robot repository and Robotics Agentic Development Harness baseline.

The reusable project/tooling structure has been carried forward and renamed to `frc2027`; completed Season2026 mechanisms, subsystems, commands, autos, and game-specific field assets are intentionally removed so students begin from a governed empty-season foundation.

> **Temporary toolchain baseline:** WPILib/vendor 2027 releases are not available yet. The repository uses the current 2026-compatible GradleRIO/vendor baseline until an official 2027 infrastructure-upgrade PR can replace it.

## Repository baseline

- mechanism-free `Robot` / `RobotContainer` shell
- reusable 2026 OI and utility patterns under `org.Griffins1884.frc2027`
- Team 1884 WPILib / Gradle / logging tooling baseline
- CODEOWNERS for mentors, software leads, and approved alumni
- PR template with risk, safety, learning, testing, and rollback evidence
- CI job `build-and-format`
- `AGENTS.md` v1 and Codex Automated Reviewer integration
- safety-critical constants isolated under protected paths
- `main` + `software-leads` governance policy
- Monitoring v1 specification, prototype digest, and sample output
- AGENTS.md stress-test findings

See [`docs/SEASON_ROLLOVER.md`](docs/SEASON_ROLLOVER.md) for exactly what was carried forward and removed.

## School-admin demo

See [`docs/SCHOOL_ADMIN_DEMO.md`](docs/SCHOOL_ADMIN_DEMO.md).

The live demo is one small change through the full governed loop:

**clarification → plan → risk → implementation → learning verification → CI → approval → PR → independent Codex review → monitoring digest → human review**.

The demo ends with the PR open and unmerged so the human authority boundary remains visible.

## Hosted governance status

The repository contains the CODEOWNERS and Harness policy for protected `main` and `software-leads` branches. GitHub-hosted rulesets/required checks must match that policy; see [`docs/BRANCH_PROTECTION_SETUP.md`](docs/BRANCH_PROTECTION_SETUP.md).
