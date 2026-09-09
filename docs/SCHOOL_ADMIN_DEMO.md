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
- shared review policy and thin Codex/Claude Code skill entry points;
- Monitoring v1 specification and sample digest;
- AGENTS.md stress-test findings describing what prompt instructions can and cannot enforce.

## Intended development loop

1. A student opens Codex or Claude Code in Season2027.
2. The agent operates as **Software Team Member**.
3. The Harness requires a semantic Robotics Scope Check before repository actions, clarification, planning, risk classification, protected-path checks, or implementation; unrelated work is denied and stopped.
4. After read-only inspection and the clarification checkpoint, the agent presents the plan and waits for explicit agreement before creating a task branch or editing.
5. Build/tests/format and full uncommitted-snapshot inspection precede actual human learning answers and evaluation; passing evidence must match before commit/push.
6. A pull request is created.
7. A fresh Codex or Claude Code review context follows the trusted-base shared template, after verified gh identity and explicit review-plan agreement.
8. Exact rendered-report approval precedes SHA-bound COMMENT publication and read-back. Record verified hosted CI/protection separately; inaccessible settings are UNVERIFIED.
9. Human Code Owners retain final merge authority.
10. Monitoring produces a readable audit digest for mentors and school oversight.

## Live demo

Use an explicitly approved harmless fixture/draft PR for the demonstration. Do not use unrelated PRs, simulate real human learning, or treat plan approval as approval of an unseen review report. Show:

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

The project does not rely on an AI prompt alone to keep students safe. `AGENTS.md` guides agent behavior, while branch protection, CODEOWNERS, CI, deterministic Harness checks, risk-based approval, review-state validation, and monitoring provide the enforceable controls and audit trail. Local guards validate routed machine-readable preconditions but cannot prove semantic scope, human understanding or context freshness. Direct shell/API calls and editable local evidence remain bypasses. Real-client loading tests and mock fixture results must be reported separately.
