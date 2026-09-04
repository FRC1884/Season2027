# Season2027 Hosted Branch Protection

Repository policy is defined in `.github/robotics-harness/governance.json`. GitHub-hosted rules should match it.

## Common baseline

Apply to all five long-lived branches:

- require a pull request before merging;
- require at least **1** approval for normal PRs;
- require appropriate CODEOWNERS/domain-owner review;
- dismiss stale approvals when new commits are pushed;
- require conversation resolution before merge;
- require `build-and-format` to pass;
- block force pushes;
- block branch deletion;
- do not allow Software Team Members to bypass the rules.

The AI review is manual/on-demand (`review PR #<number>` in the user's Codex session), so there is **no Agentic Review status check requirement**.

## Code Owner self-acceptance

[GitHub does not allow pull-request authors to approve their own PRs](https://docs.github.com/en/pull-requests/collaborating-with-pull-requests/reviewing-changes-in-pull-requests/approving-a-pull-request-with-required-reviews). Do not fabricate an approval review or lower the approval count for everyone.

To support the audited `CODEOWNER SELF-ACCEPT` path in `.github/robotics-harness/APPROVALS.md`, configure only explicitly trusted Code Owner actors as ruleset bypass actors with pull-request-only bypass mode. Keep the PR, required status checks, conversation resolution, force-push prohibition, and deletion prohibition rules enabled for everyone else. The verified author must still satisfy the Harness prerequisites before using the bypass.

GitHub ruleset bypass actors are not dynamically restricted to the paths a team owns. In the current shared long-lived-branch ruleset, adding a CODEOWNERS team would technically allow that team to bypass the whole ruleset on every included branch. The Markdown Harness can audit path ownership but cannot hard-enforce it. Use a custom GitHub App/required check or split rulesets when deterministic path-scoped self-acceptance is required; until then, grant hosted bypass only to teams whose authority genuinely covers every branch/path in that ruleset.

Before relying on self-acceptance, verify the live ruleset reports the intended bypass actor and mode. If no safe hosted bypass exists, the PR requires a different approver. Never use an ad hoc administrator bypass to work around this gap.

Resolve path ownership from the base revision's effective CODEOWNERS entries. A PR that changes CODEOWNERS, self-acceptance policy, rulesets, branch protection, or bypass actors must not bootstrap its own authority; it requires a different human authorized by the base revision.

## `main`

Final protected integration branch.

- Mentor / Software Lead / configured Code Owner governance.
- Human merge authority only.

## `software-leads`

Team-wide software integration branch below `main`.

- Software Lead ownership/review.
- Used to integrate work coming from sub-lead domains before final `main` promotion.

## `vision-localisation-lead`

Protected domain integration branch.

Owner team:

`@FRC1884/vision-localisation-lead`

## `core-mechanisms-lead`

Protected domain integration branch.

Owner team:

`@FRC1884/core-mechanisms-lead`

## `autonomous-lead`

Protected domain integration branch.

Owner team:

`@FRC1884/autonomous-lead`

## Intended flow

```text
student task branch
        ↓
relevant sub-lead branch
        ↓
software-leads
        ↓
main
```

Cross-cutting work may target `software-leads` directly when policy/ownership makes that more appropriate.

## Review groups

- `@FRC1884/mentors`
- `@FRC1884/software-lead`
- `@FRC1884/vision-localisation-lead`
- `@FRC1884/core-mechanisms-lead`
- `@FRC1884/autonomous-lead`
- `@FRC1884/approved-alumni-reviewers`

The repository files establish the intended policy and all five branches exist. GitHub-hosted ruleset/branch-protection switches are a separate control and must be enabled/verified in repository settings before students begin normal feature work.
