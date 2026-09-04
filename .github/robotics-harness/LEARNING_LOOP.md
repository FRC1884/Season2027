# Learning Verification

The learning loop is a **trust-but-verify gate** that confirms the user understands the change Codex produced. It is not satisfied by a generic explanation, approval, or checkbox.

## Verified mentor learning override

An authenticated active member of `@FRC1884/mentors` may explicitly waive the question-and-answer learning stage for their own Codex session. This is a role-based exception to this file only, not a general Harness bypass.

Before offering or using the override, Codex must run the equivalent of these read-only checks:

```text
git config --get user.name
git config --get user.email
git remote get-url origin
gh api user --jq .login
gh api orgs/FRC1884/teams/mentors/memberships/<verified-login> --jq .state
```

Eligibility requires a configured Git name and email, an origin resolving to `FRC1884/Season2027`, an authenticated GitHub login, and `state: active` for that exact login in the mentors team. The login used in the membership request must come from `gh api user`, never from local Git configuration or user-provided text.

Local `user.name`, `user.email`, and commit authorship are attribution only and can be spoofed. They never establish Mentor status. If any check is unavailable, ambiguous, or unsuccessful, fail closed and run the normal learning verification.

The eligible mentor must explicitly choose the override for the current proposed change. Record:

- repository, branch, changed-file list, and diff digest;
- local Git name and email used for attribution;
- authenticated GitHub login and active team-membership result;
- timestamp and the mentor's reason for waiving the questions;
- final result `MENTOR OVERRIDE`.

Do not record authentication tokens or private credential data. A material diff change invalidates the override and requires a new diff inspection and explicit override. The override does not satisfy or bypass planning, validation, human approval, CODEOWNERS, CI, branch protection, independent review, safety controls, deployment restrictions, or merge authority.

## Timing and subject

Run learning verification:

1. after implementation and applicable validation;
2. after Codex inspects the complete proposed commit diff, including staged and unstaged changes;
3. before creating any commit, pushing any ref, or creating/updating a pull request.

Questions must be grounded in the actual uncommitted diff, not the original request or a generic description.

If a verified mentor explicitly uses the learning override, inspect and bind the complete diff as usual, record the override evidence, and skip the question selection, user-answer, and evaluation loop below.

## Question selection

Ask the smallest set of meaningful questions that covers the important changed behavior:

- LOW risk: normally at least one question;
- MEDIUM risk: normally two or three questions;
- HIGH/CRITICAL risk: normally three to five questions, proportional to the actual diff.

Questions should test relevant understanding such as:

- why the design or policy ordering was chosen;
- control or data flow;
- subsystem or command state;
- failure behavior and safety implications;
- constants and hardware assumptions;
- tests, edge cases, and validation gaps;
- rollback or disable strategy;
- how the change interacts with existing code or governance.

For documentation or governance changes, ask about the rule being introduced, when it blocks work, what evidence proves it ran, and which controls remain human or externally enforced. Do not use trivia or questions unrelated to the diff.

## User answers

The user must answer in their own words. Codex must not answer the questions on the user's behalf, treat its own summary as the answer, or accept copied boilerplate without demonstrated understanding.

If the user asks for help or answers incorrectly, Codex may explain the relevant concept. It must then ask a new or focused follow-up question and evaluate that response; the explanation alone does not pass the gate.

## Evaluation

Evaluate every answer against the actual diff:

- correct and specific -> PASS for that question;
- materially incomplete -> explain the missing point and ask a focused follow-up;
- incorrect -> explain the mismatch and ask a new question after clarification.

Record `learning_verification_passed` only when the answers cover all meaningful changed areas. A plan acknowledgement, generic "I understand," or request to skip questions cannot substitute for PASS. Mentor/Code Owner status substitutes only through the authenticated, explicit, diff-bound override above.

## Exact-change binding

Bind the result to the proposed change by recording, where available:

- repository and branch;
- changed-file list;
- diff digest or staged-diff digest;
- risk classification;
- questions, concise user-answer summaries, and evaluations;
- timestamp and final result.

If the diff materially changes after PASS or `MENTOR OVERRIDE`, emit `learning_verification_invalidated`, inspect the revised diff, and repeat the necessary questions or record a new explicit mentor override. Non-semantic formatting may retain the result only after Codex re-inspects the diff and confirms the meaning is unchanged.

## Commit and publication gate

Until learning verification passes or a verified mentor override is recorded, do not:

- run `git commit` or create a commit through another tool;
- push a branch, tag, or commit;
- create or update a pull request.

After PASS or `MENTOR OVERRIDE`, commit only the covered change and confirm the commit matches the bound diff before pushing. Human approval, independent review, CI, and merge requirements remain separate gates.

## Evidence

Keep a concise Markdown summary outside the product diff when possible:

```markdown
# Learning Verification

## Change binding

- Repository:
- Branch:
- Changed files:
- Diff digest:
- Risk:

## Questions and evaluations

### Question 1
...

### User answer
...

### Evaluation
PASS / FOLLOW-UP REQUIRED

## Mentor override evidence (when used)

- Local Git identity:
- Authenticated GitHub login:
- Mentor team membership:
- Reason:

## Final result

PASS / MENTOR OVERRIDE / BLOCKED / INVALIDATED
```

Do not record hidden chain-of-thought. Concise questions, visible answer summaries, evaluations, and change-binding metadata are sufficient.
