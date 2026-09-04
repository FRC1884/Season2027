# Task Lifecycle

Every normal Codex coding session acts as a **Software Team Member** unless the user explicitly assigns a different authorized role or the mentor identity check below succeeds.

## 0. User-facing intercept

Interpret the user's request against the Harness before using tools, running repository commands, or inspecting repository files. The first visible action must be a concise response to the user.

For a normal request, acknowledge what Codex can do and state that repository inspection and a plan will come next. Do not make routine work confirmation-heavy.

When the request conflicts with Harness policy, contains unsafe ambiguity, requests a bypass, or requires a governed step, explain before repository actions:

1. the valid part Codex can do;
2. the conflicting part it cannot follow and the policy reason;
3. the governed alternative it will follow;
4. any clarification or user plan acknowledgement needed before implementation;
5. any separate authorized human approval that will still be required later.

The response may be natural prose; these do not need to be literal headings. Do not reject a whole request when only one instruction conflicts, and do not silently ignore the invalid instruction.

Apply this intercept to at least:

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

## 1A. Role resolution

Before applying role-dependent gates:

1. record `git config user.name`, `git config user.email`, and the origin remote for attribution and repository context;
2. resolve the authenticated login using `gh api user` rather than trusting the local Git name or email;
3. query `orgs/FRC1884/teams/mentors/memberships/<verified-login>` and require `state: active`;
4. record the result without recording credentials or tokens.

The origin must resolve to `FRC1884/Season2027`. Missing Git attribution, unavailable GitHub authentication, a mismatched repository, an absent membership, or a non-active membership means the session remains a Software Team Member. Never infer Mentor status from `user.name`, `user.email`, a commit author, or a user claim alone.

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

Apply `LEARNING_LOOP.md` to the complete uncommitted diff. Codex must ask the user meaningful questions about the actual change and evaluate the answers unless a verified mentor explicitly uses the documented learning override.

The gate passes only when the user demonstrates correct, specific understanding in their own words, or when an authenticated active `@FRC1884/mentors` member explicitly invokes and records the mentor learning override for the bound diff. Plan acknowledgement, approval, a generic "I understand," or Codex answering its own questions does not count.

Until the gate passes, do not:

- create a commit;
- push a branch or tag;
- create or update a pull request.

If the diff materially changes after PASS or after a mentor override, invalidate the result and repeat the verification or re-record the override against the revised diff before publication actions resume.

## 8. Commit

After learning verification passes or a verified mentor override is recorded, commit only the covered diff. Confirm that the resulting commit matches the files and behavior covered by the learning result or override.

## 9. Human approvals

Apply `APPROVALS.md` for protected/high-risk work. An implementation agent cannot approve itself.

## 10. Push and pull request

Push only the commit covered by the passing learning verification or verified mentor override, then create or update a PR to the appropriate integration branch. Do not merge it yourself.

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
