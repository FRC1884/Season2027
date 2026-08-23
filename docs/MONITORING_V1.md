# Monitoring Layer v1

## Goal

Give mentors and school administrators an auditable view of agent-assisted development without collecting private hidden model reasoning or turning monitoring into screen surveillance.

## Event model

The Harness should emit structured events such as:

- `session_started`
- `request_received`
- `policy_conflict_detected` / `policy_conflict_explained`
- `clarification_required` / `clarification_requested` / `clarification_answered`
- `plan_presented` / `plan_acknowledgement_requested`
- `plan_acknowledged` / `plan_rejected` / `plan_revised`
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

### Interaction ordering

The event stream should make the user-facing gates auditable without capturing model reasoning:

```text
request_received
    -> policy_conflict_detected (when applicable)
    -> policy_conflict_explained (before repository actions)
    -> clarification events (when required)
    -> plan_presented
    -> plan_acknowledgement_requested
    -> plan_acknowledged
    -> first substantial edit
```

If the user rejects the plan, emit `plan_rejected` and do not begin substantial edits. If the implementation materially changes, emit `plan_revised`, present the revised plan, and require another acknowledgement before edits resume.

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

## Privacy boundary

Monitoring records visible actions and concise user-facing decision summaries. A policy-conflict summary may record the conflicting instruction, the policy boundary, and the governed alternative; it must not attempt to capture private chain-of-thought, passwords, tokens, or other secrets.

## Mentor digest

The useful admin interface is a digest, not a raw event firehose. A digest should answer:

- Who is working and in what role?
- What task are they attempting?
- What is the current risk level?
- Which protected paths were touched?
- Were clarification, planning, learning, and approvals completed?
- Did build/tests/CI pass?
- Was any policy conflict explained before repository actions?
- Did substantial edits begin only after plan acknowledgement?
- Was a PR created?
- Has the current head been independently reviewed?
- Are there unresolved blocking findings?
- Did anything attempt to bypass governance?

## Prototype

`tools/monitoring_digest.py` converts a small JSONL event stream into a readable Markdown digest. The production Harness has a richer event store/integrity chain; the repository prototype exists so the monitoring concept can be demonstrated independently to school administration.

See `docs/samples/MONITORING_DIGEST_SAMPLE.md` for an example output.
