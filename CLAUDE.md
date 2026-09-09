@AGENTS.md

This is the Claude Code adapter for the shared robotics contract. Explicitly
read the required lifecycle documents listed in AGENTS.md before task actions;
imports do not automatically load Markdown links. Follow nested instruction
scopes and pause on contradictions. System instructions, runtime permissions,
and GitHub authorization remain separate authority layers.

For `review PR #123` or `review PR owner/repository#123`, load the project
`agentic-review` skill. This is not a replacement for a built-in `/review`.
