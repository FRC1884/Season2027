# Monitoring Contract

Monitoring exists so mentors/school administrators can reconstruct how an agent-assisted change moved through the lifecycle without exposing private chain-of-thought.

Record concise, structured events or Markdown summaries for:

- session/task start;
- Robotics Scope Check start and result using `scope_check_started`, `scope_check_passed`, `scope_check_clarification_required`, or `scope_check_denied`;
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

Scope result events should additionally identify, without recording secrets or hidden reasoning:

```text
request_objective_summary
scope_classification (IN_SCOPE / AMBIGUOUS / OUT_OF_SCOPE)
scope_reason_category / concise visible rationale
scope_clarification_status when applicable
no_work_performed (required and true for denials)
```

The event order must show that the scope check occurred after receipt of the request and before any normal Harness workflow or task action. A denied task must have no planning, research, repository-inspection, command, branch, file-change, commit, push, or PR events associated with that request. Recording the denial event itself is permitted control-plane governance evidence, not task execution.

Do not log secrets or hidden chain-of-thought. Record visible decision rationale only.

A mentor digest should summarize:

- what the user requested and the Robotics Scope Check result;
- whether scope clarification was required and answered;
- whether an out-of-scope request was denied with no work performed;
- whether normal Harness work began only after `scope_check_passed`;
- what changed;
- risk level;
- approvals;
- validation;
- review findings;
- unresolved blockers;
- final PR state.

See `docs/samples/MONITORING_DIGEST_SAMPLE.md` for the human-facing demo format.
