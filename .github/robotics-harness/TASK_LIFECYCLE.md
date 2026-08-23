# Task Lifecycle

Every normal Codex coding session acts as a **Software Team Member** unless the user explicitly assigns a different authorized role.

## 0. Robotics Scope Check

Apply the authoritative Robotics-Only Scope Policy in `/AGENTS.md` to the actual primary objective of every new request before beginning Intake or any task-specific action.

```text
USER REQUEST
      │
      ▼
ROBOTICS SCOPE CHECK
      │
      ├── CLEARLY OUT OF SCOPE
      │       │
      │       └── DENY + STOP
      │
      ├── AMBIGUOUS
      │       │
      │       └── CLARIFY
      │
      └── IN SCOPE
              │
              ▼
      EXISTING HARNESS WORKFLOW
              │
              ├── clarification
              ├── planning
              ├── risk classification
              ├── implementation
              ├── validation
              ├── learning verification
              ├── commit
              ├── push
              └── PR / review / governance
```

- Use semantic/contextual classification and the existing conversation/repository context; a robotics keyword or fictional robotics framing is not sufficient.
- For genuine ambiguity that could reasonably be robotics-related, ask one concise scope question and do not implement while waiting.
- For a clearly unrelated request, issue the prescribed denial and stop. Do not plan, research, inspect unrelated project files, run implementation commands, modify files, create a branch, commit, push, or open a PR.
- Where the existing monitoring mechanism is available, the only control-plane action allowed for a denied task is recording the required non-sensitive `scope_check_denied` event. This is governance evidence, not task execution, and must record that no work was performed.
- Continue to Intake only after `scope_check_passed`. A PASS establishes eligibility only; it does not satisfy or bypass any later Harness gate.

## 1. Intake

- Read `AGENTS.md`, this Harness directory, and relevant repository context.
- Restate the requested outcome in one concise task summary.
- Do not edit yet if material requirements are unclear.
- Record the approved scope classification before normal task work begins.

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
