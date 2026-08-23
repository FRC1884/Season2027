# Task Lifecycle

Every normal Codex coding session acts as a **Software Team Member** unless the user explicitly assigns a different authorized role.

## 1. Intake

- Read `AGENTS.md`, this Harness directory, and relevant repository context.
- Restate the requested outcome in one concise task summary.
- Do not edit yet if material requirements are unclear.

## 2. Clarification

Ask only questions that materially affect correctness, safety, architecture, hardware assumptions, or acceptance criteria.

Do not invent:

- CAN IDs;
- current limits;
- gear ratios;
- physical travel limits;
- sensor locations;
- field/game data;
- safety-critical constants.

## 3. Plan

Before substantial edits, write a concise plan containing:

- intended behavior;
- files/areas expected to change;
- acceptance criteria;
- tests/validation;
- protected paths involved;
- rollback strategy where risk warrants it.

## 4. Risk

Apply `RISK_POLICY.md`. Record the initial risk and re-evaluate against the actual diff before publication.

## 5. Implementation

- Work on a task branch.
- Keep scope within the approved plan.
- If implementation materially diverges, update the plan before continuing.
- Keep Harness evidence outside the product diff.

## 6. Validation

Run the repository's applicable build, tests, and formatting checks. Inspect the complete diff and remove unrelated changes.

## 7. Learning verification

Apply `LEARNING_LOOP.md`. The Software Team Member must demonstrate understanding of meaningful changed code before asking to publish.

## 8. Human approvals

Apply `APPROVALS.md` for protected/high-risk work. An implementation agent cannot approve itself.

## 9. Pull request

Create a PR to the appropriate integration branch. Do not merge it yourself.

Default promotion flow:

```text
task/* -> relevant sub-lead branch -> software-leads -> main
```

Low-risk cross-cutting work may target `software-leads` directly when repository policy allows.

## 10. Review

The AI review is **manual/on-demand**, not an automatic GitHub AI workflow.

A human opens a fresh Codex session and asks:

```text
review PR #<number>
```

That Codex session follows `REVIEW_POLICY.md` and `REVIEW_TEMPLATE.md`.

## 11. Human merge boundary

Only an authorized human merges after required CI, CODEOWNERS, and governance conditions are satisfied.
