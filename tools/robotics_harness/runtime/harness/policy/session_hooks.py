"""Provider hook guardrails. Local state and hook input are not host authentication.

Only positively classified reads run without an implementation gate. Arbitrary
shell commands and publication are routed to the guarded session CLI. Direct
shell/API access and disabled, unsupported or timed-out hooks remain outside this
local control; never describe this module as a sandbox or security boundary.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shlex
import sys
import uuid
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any

Gate = Callable[[str, str | None], None]


@dataclass(frozen=True)
class Decision:
    allowed: bool
    reason: str
    action: str = "read"


def classify_command(command: str) -> tuple[str, list[str]]:
    """Classify a deliberately small shell grammar; never execute supplied text."""
    if not command or re.search(r"[\n\r;|&<>`]|\$[({]", command):
        return "blocked", []
    try:
        words = shlex.split(command)
    except ValueError:
        return "blocked", []
    if not words:
        return "blocked", []
    program, *args = words
    if program in {"pwd", "ls", "cat", "head", "tail", "wc"}:
        return "read", words
    if program == "rg" and not any(a.startswith(("--pre", "--hostname-bin")) for a in args):
        return "read", words
    if program == "git" and args:
        operation = args[0]
        unsafe = ("--output", "--ext-diff", "--textconv", "--exec", "--open-files-in-pager")
        if any(a.startswith(unsafe) for a in args):
            return "blocked", words
        if operation in {
            "status",
            "diff",
            "log",
            "show",
            "rev-parse",
            "ls-files",
            "ls-tree",
            "merge-base",
        }:
            return "read", words
        if args == ["branch", "--show-current"]:
            return "read", words
        if args == ["remote", "-v"] or args == ["remote", "get-url", "origin"]:
            return "read", words
    # gh flags can change GET to POST, credentials, repository or the output file.
    # The review transport owns all GitHub requests; unknown commands stay denied.
    return "blocked", words


def _relative_path(value: str, root: Path) -> str:
    path = Path(value)
    resolved = (root / path).resolve() if not path.is_absolute() else path.resolve()
    try:
        result = resolved.relative_to(root.resolve()).as_posix()
    except ValueError as error:
        raise ValueError("path is outside the governed repository") from error
    if result == "." or result.startswith(".git/") or result == ".git":
        raise ValueError("repository metadata is not a product edit")
    return result


def _entry_operation(words: list[str], root: Path) -> tuple[str, str] | None:
    if len(words) < 4 or words[0] not in {"python3", "python3.12", "python"}:
        return None
    try:
        script = _relative_path(words[1], root)
    except ValueError:
        return None
    if script != "tools/robotics_harness/entry.py":
        return None
    route = words[2]
    # Options may precede the operation; values cannot be interpreted as verbs.
    flags = {"--root", "--session", "--provider", "--task", "--state", "--state-dir"}
    index = 3
    while index < len(words) and words[index] in flags:
        index += 2
    if index >= len(words):
        return None
    return route, words[index]


def authorize_tool(payload: dict[str, Any], *, root: Path, gate: Gate) -> Decision:
    name = str(payload.get("tool_name", ""))
    raw = payload.get("tool_input", {})
    data = raw if isinstance(raw, dict) else {"patch": raw}
    try:
        if name in {"Read", "Glob", "Grep", "read_file", "list_files", "Skill"}:
            return Decision(True, "read-only inspection or instruction loading")
        if name in {"Bash", "exec_command", "shell_command", "shell"}:
            command = str(data.get("command", data.get("cmd", "")))
            kind, words = classify_command(command)
            if kind == "read":
                return Decision(True, "positively classified read-only command")
            operation = _entry_operation(words, root)
            if operation and operation[1] == "--help" and len(words) == 4:
                return Decision(True, "helper usage inspection")
            if operation is not None:
                route, verb = operation
                if route == "author" and verb in {
                    "start",
                    "status",
                    "propose",
                    "checkpoint",
                    "branch",
                    "begin",
                    "request-owner-approval",
                    "request-mentor-override",
                    "resume",
                    "revise",
                    "validation",
                    "learning",
                    "stage",
                    "commit",
                    "push",
                    "pr",
                }:
                    # Each verb's internal preconditions are the only write authority;
                    # notably there is no tool-callable 'yes' or 'confirm' verb.
                    return Decision(
                        True, "guarded author CLI checks its own current task", "helper"
                    )
                if route == "review" and verb in {
                    "identity",
                    "show",
                    "prepare",
                    "collect",
                    "stage-report",
                    "publish",
                    "reconcile",
                }:
                    return Decision(True, "review helper enforces report/revision gates", "helper")
            return Decision(
                False,
                "Use the governed helper for writes, validation or publication; "
                "arbitrary shell is not authorized.",
                "blocked",
            )
        paths: list[str] = []
        if name in {"Write", "Edit", "MultiEdit", "write_file", "edit_file"}:
            value = data.get("file_path", data.get("path"))
            if not isinstance(value, str) or not value:
                raise ValueError("write tool did not identify a path")
            paths = [_relative_path(value, root)]
        elif name == "apply_patch":
            patch = data.get("patch", data.get("input", data.get("command", "")))
            if not isinstance(patch, str):
                raise ValueError("patch must be text")
            paths = [
                _relative_path(value, root)
                for value in re.findall(
                    r"^\*\*\* (?:Add File|Update File|Delete File|Move to): (.+)$",
                    patch,
                    re.MULTILINE,
                )
            ]
            if not paths:
                raise ValueError("patch did not identify every target")
        else:
            return Decision(
                False, "Tool is outside the verified provider adapter coverage.", "blocked"
            )
        for path in dict.fromkeys(paths):
            gate("implement", path)
        return Decision(True, "current plan and scope checks passed", "implement")
    except (ValueError, OSError, RuntimeError) as error:
        return Decision(False, str(error), "implement")


def session_directory(root: Path, session_id: str) -> Path:
    if not session_id.strip():
        raise ValueError("provider session_id is required")
    repository_key = hashlib.sha256(str(root.resolve()).encode()).hexdigest()
    session_key = hashlib.sha256(session_id.encode()).hexdigest()
    return Path.home() / ".local" / "state" / "frc1884-harness" / repository_key / session_key


def _read_context(directory: Path) -> dict[str, Any]:
    path = directory / "context.json"
    if not path.exists():
        return {}
    if path.is_symlink():
        raise ValueError("session context cannot be a symlink")
    value = json.loads(path.read_text())
    if not isinstance(value, dict):
        raise ValueError("session context must be an object")
    return value


def _write_context(directory: Path, value: dict[str, Any]) -> None:
    from harness.tasks.store import TaskStore

    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    TaskStore._write_private(directory / "context.json", json.dumps(value, sort_keys=True) + "\n")


def task_service(directory: Path) -> Any:
    from harness.monitoring.store import EventStore
    from harness.tasks.service import TaskService
    from harness.tasks.store import TaskStore

    return TaskService(
        store=TaskStore(directory / "tasks"), event_store=EventStore(directory / "events.jsonl")
    )


def register_review_pending(
    *, root: Path, session_id: str, provider: str, state_path: Path, kind: str, digest: str
) -> None:
    """Bookkeeping only: displaying a report/plan never approves it."""
    if kind not in {"plan", "report"} or not re.fullmatch(r"[0-9a-f]{64}", digest):
        raise ValueError("pending review kind/digest is invalid")
    directory = session_directory(root, session_id)
    if state_path.is_symlink() or state_path.resolve() != (directory / "review.json").resolve():
        raise ValueError("review state must be the reserved private file for this session")
    context = _read_context(directory)
    context.update({"provider": provider, "session_id": session_id, "mode": "review"})
    context["pending_review"] = {
        "state": str(state_path.resolve()),
        "kind": kind,
        "digest": digest,
    }
    context["review_state"] = str(state_path.resolve())
    _write_context(directory, context)


def _approve_owner(directory: Path, root: Path, context: dict[str, Any]) -> None:
    from harness.ai_review.session import GhTransport
    from harness.policy.session_cli import author_policy

    pending = context["pending_owner"]
    service = task_service(directory)
    record = service.show(str(context["task_id"]))
    if record.plan_digest() != pending["digest"]:
        raise ValueError("protected approval is stale")
    transport = GhTransport()
    actor = transport.identity()
    login = actor["login"]
    original = record.inspection.get("github_actor")
    if not isinstance(original, dict) or type(original.get("id")) is not int:
        raise ValueError("original author identity was not verified; no role-dependent approval")
    if context.get("github_actor") != original:
        raise ValueError("author identity binding changed")
    if actor["id"] == original["id"]:
        raise ValueError("the task author cannot provide their own protected-plan approval")
    teams = author_policy(root).get("preimplementation_approval_teams", {})
    verified: set[str] = set()
    for group in pending["groups"]:
        team = teams.get(group, "")
        if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", team):
            raise ValueError("protected approval role has no configured team")
        org, slug = team.split("/")
        member = transport.request("GET", f"orgs/{org}/teams/{slug}/memberships/{login}")
        if not isinstance(member, dict) or member.get("state") != "active":
            raise ValueError("effective human is not an active member of the required team")
        verified.add(group)
    service.reviewer_authorizations = {login: frozenset(verified)}
    for group in verified:
        service.record_approval(record.task_id, login, group)
    agreement = context.pop("student_plan_agreement", None)
    if agreement and agreement["digest"] == record.plan_digest():
        service.confirm_plan_response(
            record.task_id,
            response=agreement["response"],
            interaction_reference=agreement["reference"],
            plan_digest=agreement["digest"],
            student_identifier=record.student_identifier,
        )
        context.pop("pending_plan", None)
    context.pop("pending_owner", None)
    _write_context(directory, context)


def verified_mentor(
    root: Path, human: str, original_actor: dict[str, Any] | None = None
) -> dict[str, Any]:
    from harness.ai_review.session import GhTransport
    from harness.policy.session_cli import _git, repository_identity

    if repository_identity(root) != "FRC1884/Season2027":
        raise ValueError("the existing mentor learning exception is limited to Season2027")
    transport = GhTransport()
    actor = transport.identity()
    if actor["login"] != human or not original_actor or actor["id"] != original_actor.get("id"):
        raise ValueError("mentor override must be for the effective human's own session")
    member = transport.request("GET", f"orgs/FRC1884/teams/mentors/memberships/{actor['login']}")
    if not isinstance(member, dict) or member.get("state") != "active":
        raise ValueError("active mentors membership could not be verified")
    if not _git(root, "config", "--get", "user.name") or not _git(
        root, "config", "--get", "user.email"
    ):
        raise ValueError("Git attribution must be configured")
    return actor


def _bookkeeping_only(payload: dict[str, Any], directory: Path) -> bool:
    raw = payload.get("tool_input", {})
    data = raw if isinstance(raw, dict) else {"patch": raw}
    paths: list[str] = []
    if payload.get("tool_name") in {"Write", "Edit"}:
        value = data.get("file_path", data.get("path", ""))
        if isinstance(value, str):
            paths = [value]
    elif payload.get("tool_name") == "apply_patch":
        patch = data.get("patch", data.get("input", data.get("command", "")))
        if isinstance(patch, str):
            paths = re.findall(
                r"^\*\*\* (?:Add File|Update File|Delete File|Move to): (.+)$", patch, re.MULTILINE
            )
    inputs = (directory / "inputs").resolve()
    return bool(paths) and all(
        Path(value).is_absolute()
        and Path(value).resolve().is_relative_to(inputs)
        and Path(value).suffix in {".md", ".json"}
        for value in paths
    )


def _review_read_allowed(payload: dict[str, Any], root: Path, directory: Path) -> bool:
    """PR text cannot broaden the reviewer into arbitrary credential/file access."""
    name = str(payload.get("tool_name", ""))
    raw = payload.get("tool_input", {})
    data = raw if isinstance(raw, dict) else {}
    if name == "Skill":
        return data.get("skill") in {"agentic-review", "agentic-review-compat"}
    if name not in {"Read", "read_file", "Glob", "Grep", "list_files"}:
        command = str(data.get("command", data.get("cmd", "")))
        _, words = classify_command(command)
        operation = _entry_operation(words, root)
        return bool(operation and operation[1] == "--help" and len(words) == 4)
    value = data.get("file_path", data.get("path", ""))
    if not isinstance(value, str) or not value:
        return False
    path = (root / value).resolve()
    if path == (directory / "review.json").resolve():
        return True
    if path.is_relative_to((directory / "inputs").resolve()) and path.suffix == ".md":
        return True
    try:
        relative = path.relative_to(root.resolve()).as_posix()
    except ValueError:
        return False
    return relative in {"AGENTS.md", "CLAUDE.md"} or any(
        relative.startswith(prefix)
        for prefix in (
            ".github/robotics-harness/",
            "templates/robotics-harness/",
            ".agents/skills/agentic-review/",
            ".claude/skills/agentic-review/",
            ".codex/skills/agentic-review/",
            "docs/governance/",
            "docs/operations/",
            "docs/learning-loop/",
        )
    )


def _hook_event(payload: dict[str, Any], root: Path, provider: str, event: str) -> dict[str, Any]:
    session_id = str(payload.get("session_id", ""))
    directory = session_directory(root, session_id)
    context = _read_context(directory)
    if context and (context.get("provider") != provider or context.get("session_id") != session_id):
        raise ValueError("provider/session binding changed")
    if event == "SessionStart":
        (directory / "inputs").mkdir(parents=True, exist_ok=True, mode=0o700)
        return {
            "hookSpecificOutput": {
                "hookEventName": event,
                "additionalContext": (
                    f"Robotics Harness session {session_id}; provider {provider}. "
                    "Read AGENTS.md and the shared lifecycle documents. "
                    "No implementation/publication is unlocked by startup or resume. "
                    "Use entry.py author/review. Approval requires a contextual human response. "
                    "Built-in /review is not the harness route. "
                    f"Input/report bookkeeping directory: {directory / 'inputs'}. "
                    "Those files grant no action authority."
                ),
            }
        }
    if event == "UserPromptSubmit":
        response = str(payload.get("prompt", ""))
        response_digest = hashlib.sha256(response.encode()).hexdigest()
        reference = "user-prompt:" + str(uuid.uuid4())
        context.update({"provider": provider, "session_id": session_id})
        context["last_interaction"] = {"digest": response_digest, "reference": reference}
        context["interactions"] = [*context.get("interactions", []), context["last_interaction"]][
            -100:
        ]
        _write_context(directory, context)
        explicit = re.fullmatch(
            r"(?:yes(?:[ ,]+i approve)?|go ahead|approved?|looks good|continue|do it)[.! ]*",
            response.strip(),
            re.IGNORECASE,
        )
        pending_review = context.get("pending_review")
        if not explicit and (
            pending_review
            or context.get("pending_plan")
            or context.get("pending_owner")
            or context.get("pending_override")
        ):
            # A rejection, correction, or intervening question is not agreement.
            # Re-present the current item before accepting a later bare 'yes'.
            context.pop("pending_review", None)
            context.pop("pending_plan", None)
            context.pop("pending_owner", None)
            context.pop("student_plan_agreement", None)
            context.pop("pending_override", None)
            _write_context(directory, context)
            return {
                "hookSpecificOutput": {
                    "hookEventName": event,
                    "additionalContext": (
                        "The response did not approve the pending item. Re-present the current "
                        "plan/report after resolving this message, then request agreement again."
                    ),
                }
            }
        if context.get("pending_override") and explicit:
            pending = context["pending_override"]
            service = task_service(directory)
            task_id = str(context["task_id"])
            if service.snapshot(task_id, repository=root)["digest"] != pending["digest"]:
                raise ValueError("mentor override snapshot is stale")
            actor = verified_mentor(root, str(context["human"]), context.get("github_actor"))
            service.record_mentor_learning_override(
                task_id,
                repository=root,
                verified_login=actor["login"],
                verified_repository="FRC1884/Season2027",
                membership_state="active",
                git_attribution_present=True,
                interaction_reference=reference,
                reason=pending["reason"],
            )
            context.pop("pending_override", None)
            _write_context(directory, context)
            return {
                "hookSpecificOutput": {
                    "hookEventName": event,
                    "additionalContext": (
                        "The existing Season2027 mentor learning exception was recorded "
                        "for this snapshot. Owner, independent-review, CI and merge gates remain."
                    ),
                }
            }
        if context.get("pending_owner") and explicit:
            _approve_owner(directory, root, context)
            return {
                "hookSpecificOutput": {
                    "hookEventName": event,
                    "additionalContext": (
                        "Protected-plan approval was recorded after live identity/team checks. "
                        "Learning, human PR reviews, CI and merge authority remain required."
                    ),
                }
            }
        if pending_review and explicit:
            from harness.ai_review.session import (
                GhTransport,
                UserReviewSession,
                make_event_emitter,
                review_state_lock,
            )
            from harness.private_io import write_private_text

            path = Path(pending_review["state"])
            if path.is_symlink() or path.resolve() != (directory / "review.json").resolve():
                raise ValueError("pending review state is outside this session's reserved file")
            with review_state_lock(directory / "review.lock"):
                state = json.loads(path.read_text())
                if state.get("session_id") != session_id or state.get("provider") != provider:
                    raise ValueError("pending review belongs to a different provider/session")
                session = UserReviewSession(
                    GhTransport(),
                    state,
                    emit=make_event_emitter(state, path.parent / "events.jsonl"),
                )
                method = (
                    session.approve_plan
                    if pending_review["kind"] == "plan"
                    else session.approve_report
                )
                method(pending_review["digest"], response, reference)
                write_private_text(path, json.dumps(state, indent=2) + "\n")
                context.pop("pending_review", None)
                _write_context(directory, context)
            return {
                "hookSpecificOutput": {
                    "hookEventName": event,
                    "additionalContext": (
                        "The contextual human response approved only the exact pending review "
                        f"{pending_review['kind']}. "
                        "No implementation, merge or human review approval was granted."
                    ),
                }
            }
        pending = context.get("pending_plan")
        if pending and context.get("task_id"):
            service = task_service(directory)
            record = service.store.load(str(context["task_id"]))
            if explicit:
                missing = set(record.required_approval_groups) - {
                    approval["reviewer_group"]
                    for approval in record.approvals
                    if approval.get("plan_digest") == record.plan_digest()
                }
                if missing:
                    context["student_plan_agreement"] = {
                        "digest": str(pending),
                        "response": response,
                        "reference": reference,
                    }
                    _write_context(directory, context)
                    return {
                        "hookSpecificOutput": {
                            "hookEventName": event,
                            "additionalContext": (
                                "Plan acknowledged. Implementation stays locked until "
                                "the separately required protected-plan owner approval is verified."
                            ),
                        }
                    }
                service.confirm_plan_response(
                    record.task_id,
                    response=response,
                    interaction_reference=reference,
                    plan_digest=str(pending),
                    student_identifier=record.student_identifier,
                )
                context.pop("pending_plan", None)
                _write_context(directory, context)
                return {
                    "hookSpecificOutput": {
                        "hookEventName": event,
                        "additionalContext": (
                            "The contextual user response confirmed the current plan. "
                            "Learning and publication approvals remain separate."
                        ),
                    }
                }
        return {}
    if event != "PreToolUse":
        return {}

    def gate(action: str, path: str | None) -> None:
        if context.get("mode") == "review":
            raise ValueError("review mode cannot write product files or enter author workflow")
        if not context.get("task_id"):
            raise ValueError(
                "No approved current task; inspect, present a plan, "
                "and wait for the human response."
            )
        service = task_service(directory)
        service.guard_action(str(context["task_id"]), action=action, repository=root, path=path)

    decision = authorize_tool(payload, root=root, gate=gate)
    if (
        context.get("mode") == "review"
        and decision.allowed
        and decision.action == "read"
        and not _review_read_allowed(payload, root, directory)
    ):
        decision = Decision(
            False,
            "Review reads are limited to policy/report data; "
            "use review show for exact-revision source.",
            "blocked",
        )
    raw_input = payload.get("tool_input", {})
    data = raw_input if isinstance(raw_input, dict) else {}
    if not decision.allowed and _bookkeeping_only(payload, directory):
        decision = Decision(
            True, "minimal input/report bookkeeping; no authority granted", "bookkeeping"
        )
    if decision.action == "helper":
        _, words = classify_command(str(data.get("command", data.get("cmd", ""))))
        operation = _entry_operation(words, root)
        critical = {"--root", "--session", "--session-id", "--provider", "--state", "--events"}

        def option(flag: str) -> str:
            return (
                words[words.index(flag) + 1]
                if flag in words and words.index(flag) + 1 < len(words)
                else ""
            )

        invalid_flags = any(words.count(flag) > 1 for flag in critical) or any(
            word.startswith(tuple(flag + "=" for flag in critical)) for word in words
        )
        if invalid_flags:
            decision = Decision(
                False, "duplicate or ambiguous authority-binding options", "blocked"
            )
        elif operation and operation[0] == "author":
            if (
                context.get("mode") == "review"
                or option("--session") != session_id
                or option("--provider") != provider
                or Path(option("--root")).resolve() != root.resolve()
            ):
                decision = Decision(
                    False,
                    "author helper repository/provider/session mismatch or review-only context",
                    "blocked",
                )
        elif operation and operation[0] == "review":
            expected = (directory / "review.json").resolve()
            supplied = Path(option("--state")).resolve() if option("--state") else expected
            if (
                option("--session-id") != session_id
                or option("--provider") != provider
                or Path(option("--root")).resolve() != root.resolve()
                or supplied != expected
                or (
                    option("--events")
                    and Path(option("--events")).resolve() != (directory / "events.jsonl").resolve()
                )
                or (
                    operation[1] not in {"prepare", "identity"}
                    and context.get("review_state") != str(expected)
                )
            ):
                decision = Decision(
                    False,
                    "review helper is not bound to this native session's reserved state",
                    "blocked",
                )
            elif operation[1] == "stage-report":
                report = Path(option("--report-file")).resolve()
                if (
                    not report.is_relative_to((directory / "inputs").resolve())
                    or report.suffix != ".md"
                ):
                    decision = Decision(
                        False, "report must be Markdown input outside control state", "blocked"
                    )
    if not decision.allowed:
        from harness.monitoring.models import EventType, MonitoringEvent
        from harness.monitoring.store import EventStore

        EventStore(directory / "events.jsonl").append(
            MonitoringEvent.create(
                event_type=EventType.WRITE_BLOCKED,
                session_id=session_id,
                repository=context.get("repository", "unresolved"),
                task_identifier=context.get("task_id", ""),
                result="blocked",
                metadata={
                    "provider": provider,
                    "action": decision.action,
                    "reason": decision.reason,
                },
            )
        )
    return {
        "hookSpecificOutput": {
            "hookEventName": event,
            "permissionDecision": "allow" if decision.allowed else "deny",
            "permissionDecisionReason": decision.reason,
        }
    }


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    parser.add_argument("--provider", required=True, choices=("codex", "claude"))
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--hook-event")
    args = parser.parse_args(argv)
    event = args.hook_event or "PreToolUse"
    try:
        payload = json.load(sys.stdin)
        if not isinstance(payload, dict):
            raise ValueError("hook input must be an object")
        event = args.hook_event or str(payload.get("hook_event_name", "PreToolUse"))
        output = _hook_event(payload, args.root.resolve(), args.provider, event)
    except (ValueError, OSError, RuntimeError) as error:
        reason = "Robotics Harness blocked an unverified operation: " + str(error)
        if event == "PreToolUse":
            output = {
                "hookSpecificOutput": {
                    "hookEventName": event,
                    "permissionDecision": "deny",
                    "permissionDecisionReason": reason,
                }
            }
        else:
            output = {"decision": "block", "reason": reason}
    print(json.dumps(output))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
