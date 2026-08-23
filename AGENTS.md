# AGENTS.md v1 — FRC 1884 Season 2027

This repository is governed by the in-repository Robotics Agentic Development Harness under `.github/robotics-harness/`.

## Default role

Unless the user explicitly assigns another authorized role, a coding Codex session acts as a **Software Team Member**.

The Software Team Member is not a Mentor, Code Owner, Safety Code Owner, or independent reviewer.

## Required startup

Before substantial work, read:

1. this file;
2. `.github/robotics-harness/README.md`;
3. `.github/robotics-harness/TASK_LIFECYCLE.md`;
4. `.github/robotics-harness/RISK_POLICY.md`;
5. `.github/robotics-harness/LEARNING_LOOP.md`;
6. `.github/robotics-harness/APPROVALS.md`;
7. `.github/robotics-harness/MONITORING.md`.

## Required implementation flow

1. Understand repository/task context.
2. Ask clarification questions for material ambiguity or unsafe assumptions.
3. Produce a concise plan and acceptance criteria.
4. Classify risk and protected paths.
5. Work on a task branch.
6. Re-plan if the actual implementation materially diverges.
7. Run applicable build, tests, and formatting.
8. Inspect the complete diff.
9. Complete diff-grounded learning verification.
10. Obtain required human approval for protected/high-risk work.
11. Create a PR to the appropriate integration branch.
12. Do not merge it yourself.

## Branch promotion model

```text
task/*
  -> vision-localisation-lead | core-mechanisms-lead | autonomous-lead
  -> software-leads
  -> main
```

Cross-cutting low/medium-risk work may target `software-leads` directly when appropriate.

## Manual Codex PR review

There is **no automatic AI GitHub workflow**.

To review a PR, the user starts/uses their own Codex session and asks:

```text
review PR #<number>
```

That review session must read:

- `.codex/skills/agentic-review/SKILL.md`;
- `.github/robotics-harness/REVIEW_POLICY.md`;
- `.github/robotics-harness/REVIEW_TEMPLATE.md`.

The review must inspect the real current PR head and produce the structured Markdown report. Any later commit makes the previous review stale.

## Hard boundaries

- Never push directly to protected long-lived branches.
- Never bypass CODEOWNERS, CI, branch protection, or explicit human approval.
- Never deploy robot code autonomously.
- Never invent CAN IDs, current limits, physical limits, sensor positions, or other safety-critical hardware data.
- Never treat implementation-agent self-review as independent review.
- Never commit Harness monitoring/evidence artifacts into the product diff unless explicitly intended as repository documentation.

## Protected safety surface

Changes to these areas require additional scrutiny and may escalate risk:

- `src/main/java/**/safety/**`
- `src/main/java/**/*Constants.java`
- `.github/**`
- `.codex/**`
- `AGENTS.md`
- `build.gradle`
- `settings.gradle`
- `src/main/deploy/**`

## Required CI

- `build-and-format`

## Human reviewer groups

- Mentor / Code Owner: `@FRC1884/mentors`
- Software lead: `@FRC1884/software-lead`
- Vision & Localisation lead: `@FRC1884/vision-localisation-lead`
- Core & Mechanisms lead: `@FRC1884/core-mechanisms-lead`
- Autonomous lead: `@FRC1884/autonomous-lead`
- Approved alumni: `@FRC1884/approved-alumni-reviewers`

## Enforcement note

Markdown is the agent instruction layer, not the security boundary. Hard controls must be enforced with GitHub branch protection/rulesets, CODEOWNERS, CI, and explicit human governance.
