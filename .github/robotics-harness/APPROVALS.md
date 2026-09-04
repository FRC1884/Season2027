# Human Approval Policy

Human approval is a governance control and must remain distinct from AI review.

## Plan acknowledgement is not approval

User acknowledgement of the Proposed Plan means only that the user accepts the implementation approach and allows substantial edits to begin. It does not satisfy Code Owner, Mentor, Safety Code Owner, CI, publication, merge, or deployment requirements.

Codex must state this distinction before implementation when the planned work already requires governed approval. Informal statements such as "I'm fine with it" cannot replace an authorized approval unless they are submitted through the configured approval path by a verified person holding the required repository role.

If the implementation materially changes after acknowledgement, obtain a new plan acknowledgement. Separately re-evaluate whether any exact-state human approval has become stale.

## Learning override is not approval

The verified mentor learning override in `LEARNING_LOOP.md` only waives learning questions for that mentor's own diff-bound session. It is not approval of the implementation and does not let the implementation agent satisfy a separate Mentor, Safety Code Owner, Code Owner, independent-review, or merge requirement.

The identity check for the learning override may be reused as identity evidence, but approval must still be an explicit decision through the configured approval path and must be bound to the exact state required below.

## Roles

- **Software Team Member** — implementation agent; cannot self-approve.
- **Sub-lead** — domain reviewer for Vision & Localisation, Core & Mechanisms, or Autonomous.
- **Software Lead** — integration reviewer above sub-leads.
- **Mentor / Code Owner** — human governance reviewer.
- **Safety Code Owner** — human reviewer for HIGH-risk safety-related changes.

## Approval by risk

- LOW: normal PR/CODEOWNERS policy.
- MEDIUM: relevant sub-lead/software-lead or configured Code Owner review.
- HIGH: explicit Mentor/Safety Code Owner approval for the exact revision before publication/merge.
- CRITICAL: fail closed and follow the stricter mentor/Harness administrator policy.

## Exact-state binding

For HIGH/CRITICAL approval, record:

- repository;
- task/PR identifier;
- plan revision;
- commit/head SHA;
- diff or approval digest when available;
- human approver identity;
- approver role;
- timestamp;
- decision.

Any material change makes the approval stale.

## No substitution

The following do not count as human approval:

- Software Team Member confirmation;
- user acknowledgement of the implementation plan;
- use of the verified mentor learning override;
- Automated Reviewer output;
- an AI-generated approval record;
- a comment claiming to be a mentor without matching repository authorization.
