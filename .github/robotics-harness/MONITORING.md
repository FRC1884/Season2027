# Monitoring Contract

Monitoring exists so mentors/school administrators can reconstruct how an agent-assisted change moved through the lifecycle without exposing private chain-of-thought.

Record concise, structured events or Markdown summaries for:

- request received;
- Robotics Scope Check start, clarification, pass, or denial;
- policy conflict detected and explained to the user;
- clarification required, requested, and answered;
- plan presented, acknowledgement requested, acknowledged, rejected, or revised;
- risk classification;
- protected-path detection;
- commands/build/tests;
- mentor identity verification start/result;
- learning verification start, questions, answers, evaluations, pass/fail, and invalidation;
- mentor learning override use and invalidation;
- blocked or completed commit/push/PR actions;
- human approval request/result;
- manual Codex PR review request/result;
- reviewer findings and Software Team Member response;
- completion/blocker.

## Scope entry events

Use these event types for every new request:

```text
scope_check_started
scope_check_passed
scope_check_clarification_required
scope_check_denied
```

The required entry ordering is:

```text
request_received
    -> scope_check_started
    -> scope_check_passed
       OR scope_check_clarification_required
          -> scope clarification exchange
          -> scope_check_passed or scope_check_denied
       OR scope_check_denied
```

No normal Harness workflow or task-action event may precede `scope_check_passed`. A denied task must have no planning, research, repository-inspection, command, branch, file-change, commit, push, or PR event associated with that request. Recording `scope_check_denied` is permitted control-plane governance evidence, not task execution.

## User-facing interaction events

Use these event types where applicable:

```text
request_received
policy_conflict_detected
policy_conflict_explained
clarification_required
clarification_requested
clarification_answered
mentor_identity_check_started
mentor_identity_verified
mentor_identity_rejected
plan_presented
plan_acknowledgement_requested
plan_acknowledged
plan_rejected
plan_revised
```

The user-facing interaction sequence begins only after `scope_check_passed`. For a policy conflict, `policy_conflict_explained` must occur before any repository inspection, command, or edit event. For a substantial change, `plan_presented` and `plan_acknowledgement_requested` must precede `plan_acknowledged`, and `plan_acknowledged` must precede the first substantial edit event. A material plan change requires `plan_revised` and a new acknowledgement sequence before substantial edits resume.

## Learning and publication gate events

Use these event types where applicable:

```text
diff_inspected
learning_verification_started
learning_question_asked
learning_answer_received
learning_answer_evaluated
learning_verification_passed
learning_verification_failed
learning_verification_invalidated
mentor_learning_override_used
mentor_learning_override_invalidated
commit_blocked
commit_created
push_blocked
branch_pushed
pr_blocked
pr_created
pr_updated
```

The required ordering for a new change is:

```text
validation completed
    -> diff_inspected
    -> learning_verification_started
    -> question / answer / evaluation loop -> learning_verification_passed
       OR verified mentor identity -> mentor_learning_override_used
    -> commit_created
    -> required human approval
    -> branch_pushed
    -> pr_created or pr_updated
```

If learning verification is incomplete or fails and no valid mentor override exists, record the applicable blocked action and do not create a commit, push, or create/update a PR. If the diff materially changes after PASS or an override, record the corresponding invalidation event; a new PASS or diff-bound override must precede later publication events.

Mentor identity events should record the local Git name/email for attribution, authenticated GitHub login, repository, team-membership state, and timestamp. Never record a token. A successful identity check alone is not an override: `mentor_learning_override_used` must also record the branch, changed files, diff digest, and explicit reason.

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

Scope result events should additionally identify, without recording secrets or hidden reasoning:

```text
request_objective_summary
scope_classification (IN_SCOPE / AMBIGUOUS / OUT_OF_SCOPE)
scope_reason_category / concise visible rationale
scope_clarification_status when applicable
no_work_performed (required and true for denials)
```

Do not log secrets or hidden chain-of-thought. Record concise visible summaries only. Keep runtime monitoring artifacts outside the product diff unless they are intentionally added as repository documentation.

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
