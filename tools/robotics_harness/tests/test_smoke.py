"""Offline adapter smoke tests; fake gates are not real authorization evidence."""

from __future__ import annotations

import subprocess
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
RUNTIME = ROOT / "tools/robotics_harness/runtime"
sys.path.insert(0, str(RUNTIME if RUNTIME.is_dir() else ROOT))

from harness.policy.session_hooks import authorize_tool  # noqa: E402
from harness.risk.classifier import RiskClassifier  # noqa: E402
from harness.synchronization.provider_bundle import check_bundle  # noqa: E402


class AdapterSmokeTests(unittest.TestCase):
    def test_author_and_review_entry_points_import(self) -> None:
        launcher = ROOT / "tools/robotics_harness/entry.py"
        for route in ("author", "review"):
            result = subprocess.run(
                [sys.executable, str(launcher), route, "--help"],
                cwd=launcher.parent,
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_bundle_integrity(self) -> None:
        self.assertEqual(check_bundle(ROOT), [])

    def test_unapproved_edit_captures_attempt(self) -> None:
        attempted: list[tuple[str, str | None]] = []

        def simulated_locked_gate(action: str, path: str | None) -> None:
            attempted.append((action, path))
            raise ValueError("SIMULATED: no human plan approval")

        result = authorize_tool(
            {"tool_name": "Write", "tool_input": {"file_path": str(ROOT / "fixture.txt")}},
            root=ROOT,
            gate=simulated_locked_gate,
        )
        self.assertFalse(result.allowed)
        self.assertEqual(attempted, [("implement", "fixture.txt")])
        self.assertIn("SIMULATED", result.reason)

    def test_injection_and_publication_shell_never_execute(self) -> None:
        for command in ("cat README.md; gh api secrets", "git push", "gh pr merge 1"):
            result = authorize_tool(
                {"tool_name": "Bash", "tool_input": {"command": command}},
                root=ROOT,
                gate=lambda action, path: None,
            )
            self.assertFalse(result.allowed, command)

    def test_packaged_risk_rules_load(self) -> None:
        self.assertIsNotNone(RiskClassifier().classify([]))


if __name__ == "__main__":
    unittest.main()
