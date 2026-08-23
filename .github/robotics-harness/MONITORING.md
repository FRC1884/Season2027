# Monitoring Contract

Monitoring exists so mentors/school administrators can reconstruct how an agent-assisted change moved through the lifecycle without exposing private chain-of-thought.

Record concise, structured events or Markdown summaries for:

- session/task start;
- clarification requested/answered;
- plan created/revised;
- risk classification;
- protected-path detection;
- commands/build/tests;
- learning questions/answers/evaluation;
- human approval request/result;
- commit/push/PR creation;
- manual Codex PR review request/result;
- reviewer findings and Software Team Member response;
- completion/blocker.

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

Do not log secrets or hidden chain-of-thought. Record visible decision rationale only.

A mentor digest should summarize:

- what changed;
- risk level;
- approvals;
- validation;
- review findings;
- unresolved blockers;
- final PR state.

See `docs/samples/MONITORING_DIGEST_SAMPLE.md` for the human-facing demo format.
