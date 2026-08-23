# FRC 1884 — Season 2027

Clean robot-code and Robotics Agentic Development Harness skeleton for the 2027 season.

This repository intentionally starts **without mechanisms**. The purpose is to establish governance, CI, safety boundaries, monitoring, and the agentic development workflow before student feature work begins.

> Toolchain note: WPILib 2027 has not been released yet. The skeleton is temporarily pinned to the current supported 2026 GradleRIO toolchain and should be upgraded when the official 2027 release is available.

## Before students begin

- Branch protections and required checks enabled on `main`
- CODEOWNERS / mentor review in place
- CI build + formatting required
- `AGENTS.md` governance active
- Safety-critical constants isolated under protected paths
- Harness runtime installed and validated
- Monitoring v1 enabled
- AGENTS.md stress-test findings reviewed

## Demo goal

Push one small change through the full governed loop: clarification → plan → risk → implementation → learning verification → CI → approval → PR → independent Codex review → monitoring digest → human review.
