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

## Verified Code Owner self-acceptance

GitHub does not allow a pull-request author to approve their own PR. Therefore, an eligible Code Owner self-acceptance is an explicit Harness governance decision plus an authorized ruleset-bypass merge; it must never be represented as an approving GitHub review.

A PR author is eligible only when Codex verifies all of the following from live repository state:

- `gh api user` returns a human authenticated login that exactly matches the PR author;
- the PR targets a governed integration branch and its exact current head SHA is known;
- the base branch's effective `.github/CODEOWNERS` entry (the last matching pattern) identifies that login directly, or a team in which the login has active membership, as an owner for every changed path;
- the author also holds every additional role required by the final risk and protected paths, including Safety Code Owner or Mentor/Harness administrator status where applicable;
- the repository ruleset grants that actor a pull-request bypass path.

Self-acceptance is prohibited when the PR changes an authorization surface that could create or broaden its own eligibility, including `.github/CODEOWNERS`, this self-acceptance policy, `governance.json` self-acceptance settings, hosted rulesets, branch protection, or bypass-actor configuration. Those changes require approval from a different human who was authorized by the base revision.

Before self-acceptance, the exact current head must have:

- successful required CI, including `build-and-format`;
- all review conversations resolved;
- a fresh independent Codex review with no unresolved `REQUEST CHANGES`, `SAFETY ESCALATION`, or `REVIEW INCOMPLETE` result, and no outstanding blocking human review;
- no commits after the evidence above;
- an explicit instruction from the verified Code Owner to accept or merge that exact PR head.

Record the repository, PR, base branch, head SHA, changed-file list or digest, matched CODEOWNERS entries, verified login/team memberships, risk role, required-check results, independent-review result, timestamp, and decision `CODEOWNER SELF-ACCEPT`. Any head change invalidates the decision.

After recording the decision, Codex may execute the merge on behalf of that authenticated human only when the hosted ruleset permits it. Do not submit an `APPROVE` review, force push, disable rules, skip CI, dismiss unresolved findings, or merge through administrator override outside the configured self-acceptance path. If the hosted bypass is absent or broader than the repository can safely govern, stop and report the configuration gap.

## Approval by risk

- LOW: normal PR/CODEOWNERS policy, or verified full-path Code Owner self-acceptance.
- MEDIUM: relevant sub-lead/software-lead or configured Code Owner review, or verified full-path Code Owner self-acceptance.
- HIGH: explicit Mentor/Safety Code Owner approval for the exact revision, which may be self-acceptance only when the author is also the required Mentor/Safety Code Owner for every protected path.
- CRITICAL: fail closed and require Mentor/Harness administrator authority; self-acceptance is allowed only when the author is also the full-path Code Owner and authenticated Mentor/Harness administrator.

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

For self-acceptance, use the same exact-state fields and additionally record the PR author/authenticated-user match, CODEOWNERS resolution, current-head CI/review evidence, and ruleset-bypass eligibility.

## No substitution

The following do not count as human approval:

- Software Team Member confirmation;
- user acknowledgement of the implementation plan;
- use of the verified mentor learning override;
- Automated Reviewer output;
- an AI-generated approval record;
- a comment claiming to be a mentor without matching repository authorization.

An authenticated, exact-head `CODEOWNER SELF-ACCEPT` decision is the only exception to author/approver separation. It replaces the separate human approval for that PR but not independent Codex review, CI, safety-role matching, conversation resolution, or explicit human merge intent.
