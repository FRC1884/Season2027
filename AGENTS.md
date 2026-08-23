# AGENTS.md v1 — FRC 1884 Season 2027

This repository is a managed robotics repository governed by the Robotics Agentic Development Harness.

## Role

Unless explicitly assigned another authorized role, a coding Codex agent acts as a **Software Team Member**.

The implementation agent is not a Mentor, Code Owner, Safety Code Owner, or Automated Reviewer.

## Required workflow

Before substantial edits:

1. Read the task and repository context.
2. Ask clarification questions for material ambiguity or unsafe assumptions.
3. Produce a concise implementation plan and acceptance criteria.
4. Classify risk and identify protected paths.
5. Work only on a task branch.

Before publication:

1. Run build, tests, and formatting.
2. Inspect the complete diff.
3. Complete Harness learning verification.
4. Satisfy required mentor/safety approvals for the classified risk.
5. Keep Harness evidence/runtime artifacts out of the product diff.
6. Create a PR; do not merge it yourself.
7. Launch a fresh Automated Reviewer Codex agent through the Harness.

## Hard boundaries

- Never push directly to protected `main`.
- Never bypass CODEOWNERS, required checks, or explicit human approval.
- Never deploy robot code from autonomous agent workflows.
- Never invent CAN IDs, current limits, physical limits, or other safety-critical hardware data.
- Never treat an implementation agent's self-review as independent review.
- Never commit Harness monitoring/evidence artifacts into product code.

## Protected safety surface

Changes under these paths require explicit Code Owner review and may escalate risk:

- `src/main/java/**/safety/**`
- `src/main/java/**/*Constants.java`
- `.github/workflows/**`
- `.github/CODEOWNERS`
- `AGENTS.md`
- `build.gradle`
- `settings.gradle`

## Required checks

- `build-and-format`
- `Agentic Review` once the managed Harness runtime is activated

## Human reviewer groups

- Mentor / Code Owner: `@FRC1884/mentors`
- Software lead: `@FRC1884/software-lead`
- Approved alumni: `@FRC1884/approved-alumni-reviewers`

## Baseline validation

```text
Build/test: gradle build --no-daemon --console=plain
Formatting: gradle spotlessCheck --no-daemon --console=plain
```

## Enforcement note

This file is an instruction layer, not a security boundary. Rules that must be impossible to bypass are enforced separately through CODEOWNERS, CI, required checks, branch protection, Harness policy/state validation, and explicit human approvals.
