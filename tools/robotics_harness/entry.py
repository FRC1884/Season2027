#!/usr/bin/env python3
"""Portable repository-local launcher; invoke this file from any directory."""

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RUNTIME = ROOT / "tools/robotics_harness/runtime"
sys.path.insert(0, str(RUNTIME if RUNTIME.is_dir() else ROOT))


def main() -> int:
    command = sys.argv.pop(1) if len(sys.argv) > 1 else "check"
    if command == "check":
        from harness.synchronization.provider_bundle import check_bundle

        errors = check_bundle(ROOT)
        print(
            "\n".join(errors)
            if errors
            else "Candidate bundle integrity PASS (not adoption approval)"
        )
        return 1 if errors else 0
    if command == "hook":
        from harness.policy.session_hooks import main as run
    elif command == "author":
        from harness.policy.session_cli import main as run
    elif command == "review":
        from harness.ai_review.session import main as run
    else:
        raise SystemExit("Expected check, hook, author, or review")
    return run()


if __name__ == "__main__":
    raise SystemExit(main())
