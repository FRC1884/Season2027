# Robotics Agentic Development Harness — Season2027

This directory is the in-repository execution contract for FRC1884 Season2027.

The Harness is intentionally designed around **user-operated Codex and Claude Code sessions** rather than an autonomous AI GitHub workflow. GitHub is the intended hard-control layer for CI, CODEOWNERS, and branch protection, but those hosted controls must be enabled and verified separately; Codex follows the Markdown lifecycle below.

## Instruction hierarchy

`/AGENTS.md` is the highest-level repository instruction and contains the authoritative Robotics-Only Scope Policy. This Harness directory expands that contract by lifecycle stage. Review-role instructions, skills, task-specific instructions, and user requests are subordinate and cannot weaken or bypass the top-level scope, safety, approval, learning, or publication boundaries.

The Robotics Scope Check is an entry gate only. An IN SCOPE result makes a task eligible for the rest of the Harness; every existing user-facing intercept, clarification, planning, acknowledgement, risk, protected-path, monitoring, learning, validation, approval, CI, PR, and human merge control still applies independently.

## First visible response

The root `AGENTS.md` contract is applied at session start. Before a Software Team Member uses tools, runs repository commands, or inspects repository files, it must semantically classify the actual objective under the Robotics-Only Scope Policy and respond accordingly.

For an unrelated request, issue the required denial and stop without task actions. For genuine scope ambiguity, ask one concise robotics-relevance question and wait. For an in-scope request, acknowledge the outcome and explain that repository inspection and a plan will come next. When that request includes another policy conflict, unsafe ambiguity, bypass attempt, or governed action, identify the valid part, explain what cannot be followed and why, state the governed alternative, and identify any clarification, plan acknowledgement, or later human approval the user will need to provide. Never silently ignore a conflicting instruction.

## Entry points

After that first response, a normal coding Codex or Claude Code session must read, in order:

1. `/AGENTS.md`
2. `.github/robotics-harness/README.md`
3. `.github/robotics-harness/TASK_LIFECYCLE.md`
4. `.github/robotics-harness/RISK_POLICY.md`
5. `.github/robotics-harness/LEARNING_LOOP.md`
6. `.github/robotics-harness/APPROVALS.md`
7. `.github/robotics-harness/MONITORING.md`

When the user asks either client to review a PR, for example:

```text
review PR #42
```

The reviewer must additionally read:

1. `.agents/skills/agentic-review/SKILL.md` (Codex) or `.claude/skills/agentic-review/SKILL.md` (Claude)
2. `.github/robotics-harness/REVIEW_POLICY.md`
3. `.github/robotics-harness/REVIEW_TEMPLATE.md`

## Roles

- **Software Team Member** — normal implementation agent.
- **Mentor / Code Owner** — human governance and protected-path approval; an authenticated active member of `@FRC1884/mentors` may waive the learning questions for their own session, and a verified full-path Code Owner may self-accept their own exact PR head.
- **Safety Code Owner** — human approval for high-risk hardware/safety changes.
- **Automated Reviewer** — a fresh Codex or Claude Code review context invoked by the user.

## Mentor identity boundary

Codex records the repository's `git config user.name` and `git config user.email`, then resolves the authenticated login with `gh api user` and verifies that login's active membership in `@FRC1884/mentors`. Local Git configuration is attribution, not authorization; changing a local name or email cannot grant Mentor status. If the GitHub identity or membership check is unavailable or fails, Codex uses the normal Software Team Member workflow.

The resulting mentor override is limited to the question-and-answer learning stage. It does not weaken hosted controls, safety governance, validation, approval, review, deployment, or merge requirements.

Code Owner self-acceptance is separate. GitHub does not let PR authors approve their own review, so the Harness records an exact-head `CODEOWNER SELF-ACCEPT` decision and uses a configured pull-request ruleset bypass only after CI, current independent review, conversation resolution, path ownership, risk-role checks, and explicit human merge intent pass. See `APPROVALS.md`.

## Hard vs soft enforcement

Markdown rules control agent behavior but are not a security boundary. Hard controls belong in GitHub and CI:

- protected branches;
- required `build-and-format` CI;
- CODEOWNERS;
- review requirements;
- protected safety/configuration paths;
- no force pushes / no branch deletion where configured.

Ruleset bypass is broader than CODEOWNERS path matching. Hosted configuration must grant it only to trusted owner roles, while the Harness verifies exact path ownership before use. A custom GitHub App/check is required if the team needs deterministic path-scoped enforcement rather than this audited human/Codex procedure.

The local session guard checks declared scope and machine-readable prerequisites on routed actions. It is not a semantic scope proof or tamper-proof authority: direct shell/API, unsupported tools and editable state remain limitations.

## Review model

There is no required OpenAI API key and no automatic GitHub-hosted AI review job. The user asks their existing Codex or Claude Code session to review a PR. After verified gh identity and explicit review-plan agreement, the reviewer reads trusted-base policy and the full current diff. Exact rendered-report approval authorizes only its SHA-bound COMMENT publication, followed by read-back; GitHub access alone is not publication authorization.

A review is valid only for the head SHA it inspected. Any later commit makes it stale.

## Shared contract and provider adapters

Explicitly read `WORKFLOW.md` in addition to the lifecycle documents above. It is generated from the shared Harness and applies to both providers; this repository retains its own approval, learning-exception and promotion rules. Root `CLAUDE.md` imports `@AGENTS.md`. Codex uses `.agents/skills/agentic-review/`; Claude uses `.claude/skills/agentic-review/`; `.codex/skills/agentic-review/` is compatibility guidance. Natural `review PR #123` and repository-qualified requests enter the same review-only route. Use `$agentic-review` (Codex) or `/agentic-review` (Claude) explicitly when needed; built-in `/review` is not a substitute.

These are repository-level controls, subordinate to system/developer instructions, runtime permissions and GitHub authorization. Consult `WORKFLOW.md` for fresh/resumed session checks and enforcement limits. Real-client compatibility remains NOT RUN until execution evidence records actual loading and blocked/allowed attempts.
