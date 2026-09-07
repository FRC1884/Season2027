# Agentic Review — PR #<number>

## Review Metadata

- Repository / PR URL: `<owner/repository and URL>`
- Base: `<branch and exact SHA>`
- Head: `<branch and exact SHA>`
- Merge-base / review range: `<SHA and complete comparison range>`
- Authenticated publisher: `<verified human GitHub login>`
- Agent / client version: `<provider, client, version>`
- Reviewer role / session: `<Automated Reviewer and session identity>`
- Context / independence: `<fresh context evidence or UNVERIFIED/self-review limitation>`
- Trusted policy / template revision: `<exact revision and digests>`
- Timestamp: `<UTC>`

## Executive Result

<APPROVE | COMMENT | REQUEST CHANGES | SAFETY ESCALATION | REVIEW INCOMPLETE>

## Summary

<Describe observed changes from the actual diff.>

## Coverage

- Changed files / inspected files: `<counts and reconciliation>`
- Pagination, renames, deletions, binaries and missing patches: `<handling>`
- Surrounding code inspected: `<paths and rationale>`
- Unavailable or truncated evidence: `<limitations or verified absence>`

## Findings

<Populate severity groups CRITICAL, HIGH, MEDIUM and LOW as applicable. State no
findings only after inspection; no default clean result. Keep IDs for persisting
findings and identify new/changed/resolved findings on re-review.>

### AR-001 — <title>

**Severity:** <level>
**File:** <path>
**Lines:** <line or range when available>
**Evidence:** <observed code, test or policy evidence and revision>

**Issue**

<Grounded explanation and confidence.>

**Why this matters**

<Concrete impact.>

**Recommended action**

<Specific correction or verification.>

## Robot Safety Review

- Hardware assumptions: <verified or unknown>
- Actuation / limits / failure behavior: <assessment>
- Safety escalation required: <YES/NO and reason>

## Architecture Review

<Integration, state/timing and design observations.>

## Testing Review

- Existing CI / revision: <observed results and links>
- Checks actually performed: <commands or static inspection and results>
- Tests present / missing validation: <assessment>
- Execution isolation / limitations: <NOT RUN or separately authorized evidence>

## Governance Review

- Protected paths / CODEOWNERS: <base-policy assessment>
- Self-acceptance proposed: <YES/NO/NOT APPLICABLE>
- Ownership / authorization-surface result: <evidence or unavailable>
- Scope / policy / rollback: <assessment>
- Human approval / safety sign-off: <separate status; COMMENT never substitutes>

## Learning Considerations

<Availability of sanitized author learning evidence and relevant concepts. Do
not publish student answers, trigger author learning, or fabricate participation.>

## Positive Observations

<Important correct decisions supported by inspected evidence, or none observed.>

## Final Recommendation

<APPROVE | COMMENT | REQUEST CHANGES | SAFETY ESCALATION | REVIEW INCOMPLETE>

This is AI-assisted analysis. GitHub publication uses COMMENT only and requires
human authorization of this exact report. It is not a human approving review or
merge decision. The report is valid only for the recorded head; any later head
change makes it STALE. Base/policy changes require applicability reassessment.

## Publication

- State: <DRAFT / APPROVED / PUBLISHED / BLOCKED / FAILED / STALE>
- Report digest / approval evidence: <external evidence reference>
- Review URL / read-back evidence: <PENDING before publication; record afterward externally>
- Human actions verified: <only actions supported by evidence>
