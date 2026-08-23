---
name: "agentic-review"
description: "Use for on-demand PR review requests and the automatic review handoff after a Harness-created PR."
---

# Agentic Review

- Resolve the real repository and pull request state.
- Launch a fresh `.codex/agents/automated-reviewer.toml` agent separate from the Software Team Member.
- Review the exact current head SHA using the diff, relevant surrounding code, tests, CI and trusted policy.
- Generate a structured human-readable Markdown review with stable `AR-NNN` finding IDs.
- Record reviewer session, repository, PR number and reviewed head SHA in Harness monitoring/state.
- Publish through the configured Codex/GitHub review path when available.
- If the PR head changes, mark the old review stale and launch a fresh reviewer cycle.
- Never treat the implementation agent's self-review as independent review.
- Never merge or deploy; human governance remains authoritative.
