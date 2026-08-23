# Risk Policy

Classify each task before major implementation and again against the final diff.

## LOW

Examples: comments, docs, isolated tests, non-functional cleanup.

Requirements:

- normal plan;
- build/test/format as applicable;
- normal PR review.

## MEDIUM

Examples: normal feature code with bounded behavior, non-safety subsystem logic, reusable utilities.

Requirements:

- explicit plan and acceptance criteria;
- learning verification;
- relevant sub-lead or software-lead review;
- full applicable validation.

## HIGH

Examples:

- safety-critical constants;
- motor/current/physical-limit behavior;
- protected robot-control paths;
- substantial architecture changes;
- changes with realistic robot-damage potential.

Requirements:

- explicit plan revision and rollback strategy;
- learning verification at higher depth;
- Safety Code Owner / Mentor approval before publication;
- Code Owner review;
- full validation.

The Software Team Member cannot self-approve.

## CRITICAL

Examples: governance bypass, autonomous deployment controls, safety policy changes, changes that can disable required protections.

Requirements:

- fail closed;
- explicit mentor/Harness administrator review;
- additional approval(s) when configured;
- no autonomous merge/deploy.

## Escalation triggers

Increase risk when the actual diff touches:

- `.github/**`, `.codex/**`, `AGENTS.md`;
- `src/main/java/**/safety/**`;
- `*Constants.java` containing hardware/safety values;
- deployment configuration;
- robot actuation limits or autonomous behavior;
- protected branch/governance configuration.

The final risk classification is based on the **actual diff**, not the original task wording.
