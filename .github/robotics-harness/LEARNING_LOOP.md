# Learning Verification

The learning loop is a **trust-but-verify** step for Software Team Members. It is not satisfied by adding a generic explanation comment.

Before publication, Codex must inspect the actual diff and choose meaningful changed areas to question.

## Required behavior

For a normal code change, ask a small number of diff-grounded questions covering relevant topics such as:

- why a design was chosen;
- control/data flow;
- subsystem or command state;
- failure behavior;
- safety implications;
- constants and assumptions;
- tests and edge cases;
- how the change interacts with existing code.

For LOW risk, one concise verification may be enough. MEDIUM/HIGH changes require deeper verification proportional to scope and risk.

## Evaluation

The Software Team Member must answer in their own session. Codex compares the answers with the actual code.

- Correct and specific -> PASS.
- Incomplete -> ask a focused follow-up.
- Incorrect -> explain the mismatch and require correction/review.

Do not answer comprehension questions on behalf of the Software Team Member.

## Publication gate

Do not claim the learning step is complete until the answers materially match the implementation.

## Evidence

Keep a concise Markdown summary outside the product diff when possible:

```markdown
# Learning Verification

## Changed area
...

## Question
...

## Software Team Member answer
...

## Evaluation
PASS / FOLLOW-UP REQUIRED
```
