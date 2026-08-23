# Season2027 Hosted Branch Protection

Repository policy is defined in `.github/robotics-harness/governance.json`. GitHub-hosted rules should match it.

## Common baseline

Apply to all five long-lived branches:

- require a pull request before merging;
- require at least **1** approval;
- require appropriate CODEOWNERS/domain-owner review;
- dismiss stale approvals when new commits are pushed;
- require conversation resolution before merge;
- require `build-and-format` to pass;
- block force pushes;
- block branch deletion;
- do not allow Software Team Members to bypass the rules.

The AI review is manual/on-demand (`review PR #<number>` in the user's Codex session), so there is **no Agentic Review status check requirement**.

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
