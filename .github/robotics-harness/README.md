# Robotics Agentic Development Harness — Season2027

This directory is the in-repository execution contract for FRC1884 Season2027.

The Harness is intentionally designed around **user-operated Codex sessions** rather than an autonomous AI GitHub workflow. GitHub provides hard repository controls (CI, CODEOWNERS, branch protection); Codex follows the Markdown lifecycle below.

## Instruction hierarchy

`/AGENTS.md` is the highest-level repository instruction and contains the authoritative Robotics-Only Scope Policy. This Harness directory expands that contract by lifecycle stage. Review-role instructions, skills, task-specific instructions, and user requests are subordinate and cannot weaken or bypass the top-level scope, safety, approval, or publication boundaries.

The Robotics Scope Check is an entry gate only. An IN SCOPE result makes a task eligible for the rest of the Harness; every existing clarification, planning, risk, protected-path, monitoring, learning, validation, approval, CI, PR, and human merge control still applies independently.

## Entry points

A normal coding Codex session must read, in order:

1. `/AGENTS.md`
2. `.github/robotics-harness/README.md`
3. `.github/robotics-harness/TASK_LIFECYCLE.md`
4. `.github/robotics-harness/RISK_POLICY.md`
5. `.github/robotics-harness/LEARNING_LOOP.md`
6. `.github/robotics-harness/APPROVALS.md`
7. `.github/robotics-harness/MONITORING.md`

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
- **Mentor / Code Owner** — human governance and protected-path approval.
- **Safety Code Owner** — human approval for high-risk hardware/safety changes.
- **Automated Reviewer** — a fresh Codex review context invoked by the user.

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
