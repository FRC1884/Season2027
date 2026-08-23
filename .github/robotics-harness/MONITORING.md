# Monitoring Contract

Monitoring exists so mentors/school administrators can reconstruct how an agent-assisted change moved through the lifecycle without exposing private chain-of-thought.

Record concise, structured events or Markdown summaries for:

- request received;
- policy conflict detected and explained to the user;
- clarification required, requested, and answered;
- plan presented, acknowledgement requested, acknowledged, rejected, or revised;
- risk classification;
- protected-path detection;
- commands/build/tests;
- learning questions/answers/evaluation;
- human approval request/result;
- commit/push/PR creation;
- manual Codex PR review request/result;
- reviewer findings and Software Team Member response;
- completion/blocker.

## User-facing interaction events

Use these event types where applicable:

```text
request_received
policy_conflict_detected
policy_conflict_explained
clarification_required
clarification_requested
clarification_answered
plan_presented
plan_acknowledgement_requested
plan_acknowledged
plan_rejected
plan_revised
```

For a policy conflict, `policy_conflict_explained` must occur before any repository inspection, command, or edit event. For a substantial change, `plan_presented` and `plan_acknowledgement_requested` must precede `plan_acknowledged`, and `plan_acknowledged` must precede the first substantial edit event. A material plan change requires `plan_revised` and a new acknowledgement sequence before substantial edits resume.

Event summaries must contain only concise rationale that was visible or suitable to show to the user, such as the conflicting instruction, the governed alternative, and the approval boundary. Do not record hidden chain-of-thought.

Each event should identify, where applicable:

```text
timestamp
repository
task/session id
actor role
branch
PR number
head SHA
event type
status
short summary
```

Do not log secrets or hidden chain-of-thought. Record concise visible summaries only. Keep runtime monitoring artifacts outside the product diff unless they are intentionally added as repository documentation.

A mentor digest should summarize:

- what changed;
- risk level;
- approvals;
- validation;
- review findings;
- unresolved blockers;
- final PR state.

See `docs/samples/MONITORING_DIGEST_SAMPLE.md` for the human-facing demo format.
