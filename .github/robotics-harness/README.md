# Robotics Agentic Development Harness — Season2027

This directory is the in-repository execution contract for FRC1884 Season2027.

The Harness is intentionally designed around **user-operated Codex sessions** rather than an autonomous AI GitHub workflow. GitHub is the intended hard-control layer for CI, CODEOWNERS, and branch protection, but those hosted controls must be enabled and verified separately; Codex follows the Markdown lifecycle below.

## First visible response

The root `AGENTS.md` contract is applied at session start. Before a Software Team Member uses tools, runs repository commands, or inspects repository files, it must respond briefly to the user's request.

For a normal request, acknowledge the outcome and explain that repository inspection and a plan will come next. When the request includes a policy conflict, unsafe ambiguity, bypass attempt, or governed action, identify the valid part, explain what cannot be followed and why, state the governed alternative, and identify any clarification, plan acknowledgement, or later human approval the user will need to provide. Never silently ignore a conflicting instruction.

## Entry points

After that first response, a normal coding Codex session must read, in order:

1. `/AGENTS.md`
2. `.github/robotics-harness/TASK_LIFECYCLE.md`
3. `.github/robotics-harness/RISK_POLICY.md`
4. `.github/robotics-harness/LEARNING_LOOP.md`
5. `.github/robotics-harness/MONITORING.md`
6. `.github/robotics-harness/APPROVALS.md`

When the user asks Codex to review a PR, for example:

```text
review PR #42
```

Codex must additionally read:

1. `.codex/skills/agentic-review/SKILL.md`
2. `.github/robotics-harness/REVIEW_POLICY.md`
3. `.github/robotics-harness/REVIEW_TEMPLATE.md`

## Roles

- **Software Team Member** — normal implementation agent.
- **Mentor / Code Owner** — human governance and protected-path approval; an authenticated active member of `@FRC1884/mentors` may waive the learning questions for their own session.
- **Safety Code Owner** — human approval for high-risk hardware/safety changes.
- **Automated Reviewer** — a fresh Codex review context invoked by the user.

## Mentor identity boundary

Codex records the repository's `git config user.name` and `git config user.email`, then resolves the authenticated login with `gh api user` and verifies that login's active membership in `@FRC1884/mentors`. Local Git configuration is attribution, not authorization; changing a local name or email cannot grant Mentor status. If the GitHub identity or membership check is unavailable or fails, Codex uses the normal Software Team Member workflow.

The resulting mentor override is limited to the question-and-answer learning stage. It does not weaken hosted controls, safety governance, validation, approval, review, deployment, or merge requirements.

## Hard vs soft enforcement

Markdown rules control agent behavior but are not a security boundary. Hard controls belong in GitHub and CI:

- protected branches;
- required `build-and-format` CI;
- CODEOWNERS;
- review requirements;
- protected safety/configuration paths;
- no force pushes / no branch deletion where configured.

## Review model

There is no required OpenAI API key and no automatic GitHub-hosted AI review job. The user asks their own Codex instance to review a PR. The reviewer reads the exact PR head, applies the policy in this directory, writes a structured Markdown report, and may post it to the PR when GitHub access is available.

A review is valid only for the head SHA it inspected. Any later commit makes it stale.
