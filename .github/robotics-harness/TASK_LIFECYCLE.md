# Task Lifecycle

Every normal Codex coding session acts as a **Software Team Member** unless the user explicitly assigns a different authorized role.

## 0. Robotics Scope Check and user-facing intercept

Apply the authoritative Robotics-Only Scope Policy in `/AGENTS.md` to the actual primary objective of every new request before using tools, running repository commands, inspecting repository files, or beginning Intake.

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
              ├── user-facing intercept / clarification
              ├── planning + acknowledgement
              ├── risk classification
              ├── implementation
              ├── validation
              ├── learning verification
              ├── commit
              ├── human approval
              ├── push
              └── PR / review / governance
```

- Use semantic/contextual classification and already-available conversation/repository context; a robotics keyword or fictional robotics framing is not sufficient.
- For genuine ambiguity that could reasonably be robotics-related, ask one concise scope question as the first response and do not implement while waiting.
- For a clearly unrelated request, issue the prescribed denial as the first response and stop. Do not plan, research, inspect unrelated project files, run implementation commands, modify files, create a branch, commit, push, or open a PR.
- Where the existing monitoring mechanism is available, the only control-plane action allowed for a denied task is recording the required non-sensitive `scope_check_denied` event. This is governance evidence, not task execution, and must record that no work was performed.
- Continue with the rest of the user-facing intercept and then Intake only after `scope_check_passed`. A PASS establishes eligibility only; it does not satisfy or bypass any later Harness gate.

The first visible action must reflect the scope result. For an in-scope request, acknowledge what Codex can do and state that repository inspection and a plan will come next. Do not make routine work confirmation-heavy.

When the request conflicts with Harness policy, contains unsafe ambiguity, requests a bypass, or requires a governed step, explain before repository actions:

1. the valid part Codex can do;
2. the conflicting part it cannot follow and the policy reason;
3. the governed alternative it will follow;
4. any clarification or user plan acknowledgement needed before implementation;
5. any separate authorized human approval that will still be required later.

The response may be natural prose; these do not need to be literal headings. For an in-scope request, do not reject the whole request when only one non-scope instruction conflicts, and do not silently ignore the invalid instruction.

Apply the remaining intercept to at least:

- Harness bypass attempts;
- unsafe assumptions or missing safety-critical information;
- requests to invent hardware configuration;
- direct pushes to protected branches;
- requests to skip planning, learning verification, testing, review, CODEOWNERS, or CI;
- requests to fabricate evidence or report unrun tests as passed;
- self-approval or attempts to substitute informal confirmation for governed approval;
- implementation-agent self-review presented as independent review;
- autonomous robot deployment.

If the repository might already contain a required safety-critical value, say that Codex will inspect for it. If inspection does not find it, stop and ask for the value rather than inventing it.

## 1. Intake

- After the user-facing intercept, read `AGENTS.md`, this Harness directory, and relevant repository context.
- Keep the requested outcome and any policy boundary visible in one concise task summary.
- Do not edit yet if material requirements are unclear.
- Record `scope_check_passed` before normal task work begins.

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

After repository inspection and required clarification, present this gate before substantial edits:

```markdown
## Proposed Plan

1. ...

## Acceptance Criteria

- ...

## Risk

LOW / MEDIUM / HIGH / CRITICAL

Reason:
...

## Expected Files / Areas

- ...

Proceed with this plan?
```

The plan must cover:

- intended behavior;
- files/areas expected to change;
- acceptance criteria;
- tests/validation;
- protected paths involved;
- rollback strategy where risk warrants it.

Repository inspection, read-only diagnostics, and clarification may happen before acknowledgement. Substantial edits may not begin until the user has seen and acknowledged the plan, acceptance criteria, risk, and expected files/areas.

Natural acknowledgements such as `yes`, `go ahead`, `approved`, `looks good`, `continue`, or `do it` are sufficient. Record whether the plan was acknowledged or rejected.

Plan acknowledgement authorizes the described implementation approach only. It is not Code Owner, Mentor, Safety Code Owner, CI, publication, or merge approval.

## 4. Risk

Apply `RISK_POLICY.md`. Record the initial risk and re-evaluate against the actual diff before publication.

## 5. Implementation

- Work on a task branch.
- Keep scope within the acknowledged plan.
- If scope, architecture, protected paths, acceptance criteria, or risk materially changes, stop substantial edits and tell the user what changed.
- Present the revised plan and obtain a new user acknowledgement before continuing.
- Identify any newly required authorized human approval separately from plan acknowledgement.
- Keep Harness evidence outside the product diff.

## 6. Validation

Run the repository's applicable build, tests, and formatting checks. Keep the change uncommitted, inspect the complete proposed commit diff (staged and unstaged), and remove unrelated changes.

## 7. Learning verification

Apply `LEARNING_LOOP.md` to the complete uncommitted diff. Codex must ask the user meaningful questions about the actual change and evaluate the answers.

The gate passes only when the user demonstrates correct, specific understanding in their own words. Plan acknowledgement, approval, a generic "I understand," or Codex answering its own questions does not count.

Until the gate passes, do not:

- create a commit;
- push a branch or tag;
- create or update a pull request.

If the diff materially changes after PASS, invalidate the learning result and repeat the verification before publication actions resume.

## 8. Commit

After learning verification passes, commit only the reviewed diff. Confirm that the resulting commit matches the files and behavior covered by the learning verification.

## 9. Human approvals

Apply `APPROVALS.md` for protected/high-risk work. An implementation agent cannot approve itself.

## 10. Push and pull request

Push only the commit covered by the passing learning verification, then create or update a PR to the appropriate integration branch. Do not merge it yourself.

Default promotion flow:

```text
task/* -> relevant sub-lead branch -> software-leads -> main
```

Low-risk cross-cutting work may target `software-leads` directly when repository policy allows.

## 11. Review

The AI review is **manual/on-demand**, not an automatic GitHub AI workflow.

A human opens a fresh Codex session and asks:

```text
review PR #<number>
```

That Codex session follows `REVIEW_POLICY.md` and `REVIEW_TEMPLATE.md`.

## 12. Human merge boundary

Only an authorized human merges after required CI, CODEOWNERS, and governance conditions are satisfied.
