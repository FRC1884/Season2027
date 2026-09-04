# Manual Codex Pull-Request Review Policy

This repository does **not** use an automatic AI GitHub workflow.

A user starts a fresh Codex session and asks:

```text
review PR #<number>
```

That session acts as **Automated Reviewer** for the requested review only.

## Required review inputs

Resolve and inspect the real current PR state:

- repository and PR number;
- title/body;
- base branch and SHA;
- head branch and exact head SHA;
- changed files and full diff;
- relevant surrounding code;
- CI/build/test results when available;
- `AGENTS.md` and this Harness policy;
- CODEOWNERS/protected-path implications.
- any proposed Code Owner self-acceptance evidence, including base-revision ownership and authorization-surface changes.

Do not trust the implementation agent's summary as authoritative evidence.

## Review focus

Prioritize substantive findings:

- correctness;
- robot safety;
- WPILib/command lifecycle issues;
- hardware assumptions;
- concurrency/state/timing;
- failure behavior;
- architecture and integration;
- tests and simulation;
- protected-path/governance violations;
- invalid or self-bootstrapping Code Owner self-acceptance;
- secrets, generated junk, or unrelated changes;
- maintainability risks that materially affect the team.

Avoid style-only noise unless it creates a concrete maintenance/correctness issue.

## Independence

Use a fresh review context. Do not treat the Software Team Member's self-review as independent review.

## Head-SHA binding

Every report must record the exact reviewed head SHA. If the PR changes afterward, the old report is **STALE** and the user must ask Codex to review the new head again.

## Result vocabulary

Use one of:

- `APPROVE` — no blocking findings;
- `COMMENT` — advisory findings only;
- `REQUEST CHANGES` — blocking correctness/governance findings;
- `SAFETY ESCALATION` — requires explicit human safety review;
- `REVIEW INCOMPLETE` — insufficient evidence to review safely.

These are independent Codex review results, not GitHub approval reviews. An `APPROVE` result may satisfy the independent-review prerequisite for Code Owner self-acceptance, but it does not grant self-acceptance or authorize merge by itself.

## Publishing

When GitHub access is available, post a coherent review summary to the PR and use inline comments only where they improve precision. Never merge or approve on behalf of a human.

Always produce the human-readable Markdown report using `REVIEW_TEMPLATE.md`.
