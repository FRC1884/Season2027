# Robotics Agentic Development Harness — School Admin Demo

## What is built before students touch the robot code

Season2027 is a rollover of the Season2026 project/tooling baseline with the 2026 mechanisms, subsystems, game-specific commands, autos, and field assets removed. The repository starts from a governed shell instead of from a finished robot.

The skeleton includes:

- `main` and `software-leads` governance policy;
- CODEOWNERS routing for mentors, software leads, and approved alumni;
- pull-request template with risk, safety, learning, testing, and rollback evidence;
- CI for build, tests, and formatting;
- `AGENTS.md` v1 for the Software Team Member workflow;
- safety-critical constants isolated under protected paths;
- Codex Automated Reviewer configuration and review skill;
- Monitoring v1 specification and sample digest;
- AGENTS.md stress-test findings describing what prompt instructions can and cannot enforce.

## Intended development loop

1. A student opens Codex in Season2027.
2. Codex operates as **Software Team Member**.
3. The Harness requires a semantic Robotics Scope Check before clarification, planning, risk classification, protected-path checks, or implementation; unrelated work is denied and stopped.
4. Work occurs on a task branch, not `main`.
5. Build, tests, formatting, learning verification, and pre-PR checks run.
6. A pull request is created.
7. A fresh Codex **Automated Reviewer** reviews the exact current head SHA independently.
8. GitHub CI and deterministic Harness checks enforce the current review/build state.
9. Human Code Owners retain final merge authority.
10. Monitoring produces a readable audit digest for mentors and school oversight.

## Live demo

Push one small, simulation-safe change through the complete loop and show:

- task + Robotics Scope Check + clarification;
- plan + risk classification;
- implementation diff;
- learning-loop questions and answers;
- CI results;
- protected-path / governance state;
- independent automated review;
- final human review boundary;
- sample monitoring digest.

The demo should finish with the PR **open and unmerged** so the human approval boundary is visible.

## Why this matters for school administration

The project does not rely on an AI prompt alone to keep students safe. `AGENTS.md` guides agent behavior, while branch protection, CODEOWNERS, CI, deterministic Harness checks, risk-based approval, review-state validation, and monitoring provide the enforceable controls and audit trail. The Robotics-Only Scope Policy is currently instruction-level and auditable through monitoring; this repository does not yet include a deterministic runtime classifier that blocks unrelated tasks before agent action.
