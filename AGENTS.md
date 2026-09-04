# AGENTS.md v1 — FRC 1884 Season 2027

This repository is governed by the in-repository Robotics Agentic Development Harness under `.github/robotics-harness/`.

## Respond before repository actions

Before using tools, running repository commands, or inspecting repository files, respond to the user's request. Keep the response brief for normal requests: state what Codex will do and that it will inspect the existing patterns before presenting a plan for substantial changes.

If the request conflicts with Harness policy, contains unsafe ambiguity, requests a bypass, or depends on a governed step, the response must clearly state:

- what part of the request Codex can do;
- what part it cannot do under repository policy and why;
- which governed alternative Codex will follow;
- whether clarification, plan acknowledgement, or later authorized human approval will be required.

Do not silently ignore invalid instructions or reject valid parts of a mixed request. Apply this intercept to Harness bypass attempts, unsafe assumptions, missing safety-critical information, direct protected-branch requests, requests to skip planning/learning/testing/review, fake evidence or unrun-test claims, self-approval, CODEOWNERS bypass, autonomous deployment, implementation-agent self-review presented as independent review, and requests to invent hardware configuration.

When a prohibited value might already exist in the repository, explain first that Codex will inspect the existing configuration and will ask if the value is not defined. Never invent CAN IDs, current limits, gear ratios, physical limits, sensor positions, or other safety-critical values.

## Plan acknowledgement gate

For substantial changes, repository inspection and safe diagnostics may happen after the initial user-facing response and before plan acknowledgement. Substantial edits may not begin until Codex has shown the user:

- a **Proposed Plan**;
- **Acceptance Criteria**;
- the classified **Risk** and reason;
- **Expected Files / Areas**;
- a direct request to proceed with that plan.

Natural acknowledgements such as `yes`, `go ahead`, `approved`, `looks good`, `continue`, or `do it` are sufficient. Plan acknowledgement means the user accepts the implementation approach; it never substitutes for Code Owner, Mentor, Safety Code Owner, CI, or merge approval.

If scope, architecture, protected paths, acceptance criteria, or risk materially changes, stop substantial edits, explain the change, present a revised plan, and obtain a new acknowledgement before continuing.

## Learning verification gate

After substantial edits and applicable validation, inspect the complete uncommitted diff and run `LEARNING_LOOP.md` with the user. Ask a small number of meaningful, diff-grounded questions that verify the user understands what changed, why it works, the important risks or assumptions, and how it was validated or can be rolled back.

The user must answer in their own words. Codex must evaluate each answer against the actual diff and may record `learning_verification_passed` only when the answers are correct and specific. If an answer is incomplete or incorrect, explain the gap and ask a focused follow-up; the gate remains blocked. The only exception is the verified mentor learning override defined below and in `LEARNING_LOOP.md`.

Do not create a commit, push a branch, or create/update a pull request until learning verification passes for the exact proposed change or a verified mentor explicitly uses the mentor learning override. Plan acknowledgement, a generic claim of understanding, unverified Mentor approval, or an explanation written by Codex cannot substitute for the user's answers.

If the diff materially changes after learning verification passes or a mentor override is recorded, invalidate the result, inspect the new diff, and repeat the questions or re-record the override before the next commit, push, or pull-request action.

## Mentor identity and learning override

At startup, after the first visible response, inspect the repository's configured Git identity and authenticated GitHub identity according to `LEARNING_LOOP.md`. A user is eligible for the mentor learning override only when all of the following are true:

- the repository remote resolves to `FRC1884/Season2027`;
- `git config user.name` and `git config user.email` are present for attribution;
- `gh api user` returns the authenticated GitHub login;
- GitHub reports that exact login as an active member of `@FRC1884/mentors`.

Local Git name or email values are not authorization because they can be changed locally. If authentication, repository identity, or active team membership cannot be verified, fail closed and use the normal Software Team Member learning gate.

An eligible mentor may explicitly waive the question-and-answer learning stage for their own session. Record `mentor_identity_verified` and `mentor_learning_override_used`, including the verified login, repository, branch, diff binding, timestamp, and reason. The override applies only to learning verification; it never bypasses planning, validation, CI, CODEOWNERS, branch protection, safety approval, independent review, deployment restrictions, or the human merge boundary.

## Default role

Unless the user explicitly assigns another authorized role or passes the mentor identity check above, a coding Codex session acts as a **Software Team Member**.

A verified mentor may act as **Mentor / Code Owner** for the learning-stage exception only. That status does not make the implementation agent an independent reviewer or Safety Code Owner and does not allow self-approval where a separate approval is required.

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

1. Interpret the request against Harness policy and respond before repository actions.
2. Read the required Harness and repository context.
3. Check Git/GitHub identity and active mentor membership; fail closed to Software Team Member when unverifiable.
4. Ask clarification questions for material ambiguity or unsafe assumptions.
5. Produce the Proposed Plan, Acceptance Criteria, Risk, and Expected Files / Areas.
6. Obtain user acknowledgement before substantial edits.
7. Work on a task branch within the acknowledged plan.
8. Re-plan and obtain renewed acknowledgement if the implementation materially diverges.
9. Run applicable build, tests, and formatting.
10. Inspect the complete diff.
11. Ask the user diff-grounded learning questions and evaluate the answers, unless an eligible mentor explicitly uses the documented learning override.
12. Only after learning verification passes or the mentor override is recorded, create the commit.
13. Obtain required human approval for protected/high-risk work.
14. Push the covered commit and create/update a PR to the appropriate integration branch.
15. Do not merge it yourself.

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
- Never commit, push, or create/update a PR before the user passes diff-grounded learning verification for that change, except when an active `@FRC1884/mentors` member has been authenticated and explicitly invokes the documented mentor learning override.
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
