---
name: "agentic-review"
description: "Use when the user asks this Codex session to review a pull request, for example `review PR #12`."
---

# Agentic Review

This repository uses **manual/on-demand Codex review**. There is no automatic AI GitHub workflow.

When the user asks:

```text
review PR #<number>
```

or a repository-qualified equivalent, do the following:

1. Read `AGENTS.md`.
2. Read `.github/robotics-harness/REVIEW_POLICY.md`.
3. Read `.github/robotics-harness/REVIEW_TEMPLATE.md`.
4. Resolve the real current GitHub PR state and exact head SHA.
5. Treat this review context as the `Automated Reviewer` role, separate from the implementation task context.
6. Inspect the complete diff, relevant surrounding code, CI/tests, protected paths, CODEOWNERS implications, robotics safety, and architecture.
7. Produce the human-readable Markdown report using the template and stable `AR-NNN` finding IDs.
8. If GitHub write access is available, publish a concise review summary and precise inline comments where useful.
9. Never merge, deploy, or impersonate a human approver.
10. State the exact reviewed head SHA. A later commit makes this review stale and requires a new review request.

Do not require an `OPENAI_API_KEY`; the user's active Codex session is the AI runtime.
