# Learning Verification

The learning loop is a **trust-but-verify gate** that confirms the user understands the change Codex produced. It is not satisfied by a generic explanation, approval, or checkbox.

## Timing and subject

Run learning verification:

1. after implementation and applicable validation;
2. after Codex inspects the complete proposed commit diff, including staged and unstaged changes;
3. before creating any commit, pushing any ref, or creating/updating a pull request.

Questions must be grounded in the actual uncommitted diff, not the original request or a generic description.

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

Record `learning_verification_passed` only when the answers cover all meaningful changed areas. A plan acknowledgement, Mentor/Code Owner approval, generic "I understand," or request to skip questions cannot substitute for PASS.

## Exact-change binding

Bind the result to the proposed change by recording, where available:

- repository and branch;
- changed-file list;
- diff digest or staged-diff digest;
- risk classification;
- questions, concise user-answer summaries, and evaluations;
- timestamp and final result.

If the diff materially changes after PASS, emit `learning_verification_invalidated`, inspect the revised diff, and repeat the necessary questions. Non-semantic formatting may retain the result only after Codex re-inspects the diff and confirms the meaning is unchanged.

## Commit and publication gate

Until learning verification passes, do not:

- run `git commit` or create a commit through another tool;
- push a branch, tag, or commit;
- create or update a pull request.

After PASS, commit only the reviewed change and confirm the commit matches the verified diff before pushing. Human approval, independent review, CI, and merge requirements remain separate gates.

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

## Final result

PASS / BLOCKED / INVALIDATED
```

Do not record hidden chain-of-thought. Concise questions, visible answer summaries, evaluations, and change-binding metadata are sufficient.
