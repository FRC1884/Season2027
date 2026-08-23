# Human Approval Policy

Human approval is a governance control and must remain distinct from AI review.

## Plan acknowledgement is not approval

User acknowledgement of the Proposed Plan means only that the user accepts the implementation approach and allows substantial edits to begin. It does not satisfy Code Owner, Mentor, Safety Code Owner, CI, publication, merge, or deployment requirements.

Codex must state this distinction before implementation when the planned work already requires governed approval. Informal statements such as "I'm fine with it" cannot replace an authorized approval unless they are submitted through the configured approval path by a verified person holding the required repository role.

If the implementation materially changes after acknowledgement, obtain a new plan acknowledgement. Separately re-evaluate whether any exact-state human approval has become stale.

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
- Automated Reviewer output;
- an AI-generated approval record;
- a comment claiming to be a mentor without matching repository authorization.
