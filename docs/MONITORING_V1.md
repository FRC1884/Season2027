# Monitoring Layer v1

## Goal

Give mentors and school administrators an auditable view of agent-assisted development without collecting private hidden model reasoning or turning monitoring into screen surveillance.

## Event model

The Harness should emit structured events such as:

- `session_started`
- `task_received`
- `scope_check_started`
- `scope_check_passed` / `scope_check_clarification_required` / `scope_check_denied`
- `clarification_requested` / `clarification_answered`
- `plan_created` / `plan_corrected`
- `risk_classified`
- `protected_path_detected`
- `file_changed`
- `command_executed`
- `test_started` / `test_completed`
- `learning_question_asked`
- `learning_answer_received` / `learning_answer_evaluated`
- `approval_requested` / `approval_granted` / `approval_denied`
- `pre_pr_check_started` / `pre_pr_check_completed`
- `commit_created`
- `branch_pushed`
- `pr_created`
- `review_agent_started`
- `review_completed` / `review_published`
- `review_marked_stale` / `review_rerun_completed`
- `session_completed`

Each event should include the minimum useful metadata:

```text
timestamp
repository
branch
task_id
session_id
actor_role
event_type
status
summary
PR/head SHA references where applicable
integrity link/hash
```

Scope result events should also include:

```text
request_objective_summary
scope_classification (IN_SCOPE / AMBIGUOUS / OUT_OF_SCOPE)
scope_reason_category
scope_clarification_status where applicable
no_work_performed (true for scope_check_denied)
```

Event ordering is part of the audit contract. Except for `session_started`, `task_received`, scope events, and a scope clarification exchange, no normal lifecycle or task-action event may precede `scope_check_passed`. A denied task must end with `scope_check_denied`; there must be no plan, research, repository-inspection, command, branch, file-change, commit, push, or PR event for that request. The denial event is control-plane governance evidence, not task execution.

## Privacy boundary

Monitoring records visible actions and concise decision summaries. It must not attempt to capture private chain-of-thought, passwords, tokens, or other secrets.

## Mentor digest

The useful admin interface is a digest, not a raw event firehose. A digest should answer:

- Who is working and in what role?
- What task are they attempting?
- Was the task classified IN_SCOPE, AMBIGUOUS, or OUT_OF_SCOPE, and why?
- Was scope clarification required, and did normal work wait for a PASS?
- If denied, does the record confirm that no prohibited task action occurred?
- What is the current risk level?
- Which protected paths were touched?
- Were clarification, planning, learning, and approvals completed?
- Did build/tests/CI pass?
- Was a PR created?
- Has the current head been independently reviewed?
- Are there unresolved blocking findings?
- Did anything attempt to bypass governance?

## Prototype

`tools/monitoring_digest.py` converts a small JSONL event stream into a readable Markdown digest. It counts arbitrary event types, including the scope events above, but does not validate event ordering or enforce the Robotics Scope Check. The production Harness has a richer event store/integrity chain; the repository prototype exists so the monitoring concept can be demonstrated independently to school administration.

See `docs/samples/MONITORING_DIGEST_SAMPLE.md` for an example output.
