#!/usr/bin/env python3
"""Turn Harness JSONL events into a small human-readable Markdown digest."""

from __future__ import annotations

import json
import sys
from collections import Counter
from pathlib import Path


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: monitoring_digest.py <events.jsonl>", file=sys.stderr)
        return 2

    events = []
    for line in Path(sys.argv[1]).read_text(encoding="utf-8").splitlines():
        if line.strip():
            events.append(json.loads(line))

    if not events:
        print("# Monitoring Digest\n\nNo events recorded.")
        return 0

    latest = events[-1]
    counts = Counter(str(event.get("event_type", "unknown")) for event in events)
    roles = sorted({str(event.get("actor_role", "unknown")) for event in events})

    print("# Monitoring Digest")
    print()
    print(f"- Repository: `{latest.get('repository', 'unknown')}`")
    print(f"- Branch: `{latest.get('branch', 'unknown')}`")
    print(f"- Task: `{latest.get('task_id', 'unknown')}`")
    print(f"- Events: **{len(events)}**")
    print(f"- Actor roles: {', '.join(roles)}")
    print()
    print("## Event summary")
    print()
    for event_type, count in sorted(counts.items()):
        print(f"- `{event_type}`: {count}")
    print()
    print("## Latest status")
    print()
    print(str(latest.get("summary", latest.get("status", "No summary"))))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
