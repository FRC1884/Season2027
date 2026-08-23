# Season2027 Hosted Branch Protection

Repository policy is defined in `.github/robotics-harness/governance.json`. GitHub-hosted rules should match it.

## `main`

- Require a pull request before merging.
- Require at least **1** approval.
- Require review from CODEOWNERS.
- Dismiss stale approvals when new commits are pushed.
- Require conversation resolution before merge.
- Require `build-and-format` to pass.
- Require `Agentic Review` once the managed Harness review-state validator is installed and has produced the check at least once.
- Require branch to be up to date before merge where practical.
- Block force pushes and branch deletion.
- Do not allow Software Team Members to bypass these requirements.

## `software-leads`

Use the same baseline protection as `main`:

- PR required;
- 1 approval;
- CODEOWNERS review;
- stale approvals dismissed;
- conversations resolved;
- `build-and-format` required;
- `Agentic Review` required once the runtime/check is active;
- no force push or deletion.

## Intended flow

```text
student task branch
        ↓
software-leads PR / review where the team chooses to stage integration
        ↓
main PR
        ↓
human mentor / Code Owner authority
```

Direct `main` pushes are not the student workflow.

## Review groups

- `@FRC1884/mentors`
- `@FRC1884/software-lead`
- `@FRC1884/approved-alumni-reviewers`

The repository files establish ownership and desired policy, but hosted GitHub branch/ruleset configuration is a separate control and must be verified in GitHub settings before students begin normal feature work.
