"""User-session author entry point backed by the existing task/event stores.

This CLI never manufactures human responses. Plan confirmation is captured by
the provider UserPromptSubmit hook; learning input must match a captured human
interaction. These are inspectable local guardrails, not host authentication.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
from collections.abc import Sequence
from pathlib import Path
from typing import Any

from harness.monitoring.models import EventType, MonitoringEvent
from harness.policy.session_hooks import (
    _read_context,
    _write_context,
    session_directory,
    task_service,
    verified_mentor,
)
from harness.tasks.models import ImplementationPlan, TaskSpecification, TaskState


def _json_file(path: Path) -> dict[str, Any]:
    data = json.loads(path.read_text())
    if not isinstance(data, dict):
        raise ValueError("input must be a JSON object")
    return data


def _git(root: Path, *arguments: str) -> str:
    return subprocess.run(
        ["git", *arguments], cwd=root, check=True, capture_output=True, text=True
    ).stdout.strip()


def repository_identity(root: Path) -> str:
    url = _git(root, "remote", "get-url", "origin")
    match = re.fullmatch(
        r"(?:https://github\.com/|git@github\.com:)([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+?)(?:\.git)?/?",
        url,
    )
    if not match:
        raise ValueError("origin must resolve unambiguously to an expected GitHub repository")
    return match[1]


def author_policy(root: Path) -> dict[str, Any]:
    """Resolve explicit target policy; never infer a main-branch fallback."""
    repository = repository_identity(root)
    paths = [
        root / "config/agent_targets.json",
        Path(__file__).resolve().parents[2] / "config/agent_targets.json",
    ]
    for path in paths:
        if not path.is_file():
            continue
        matches = [
            value
            for value in _json_file(path).get("profiles", {}).values()
            if value.get("repository") == repository
        ]
        if len(matches) == 1 and matches[0].get("author_task_bases"):
            result: dict[str, Any] = matches[0]
            return result
    raise ValueError(
        "explicit author promotion/approval profile is unavailable for this repository"
    )


def author_identity(human: str) -> dict[str, Any] | None:
    """Unavailable auth never turns a typed label into role authority."""
    from harness.ai_review.session import GhTransport, ReviewBlocked

    try:
        actor = GhTransport().identity()
    except ReviewBlocked:
        return None
    if actor["login"] != human or type(actor.get("id")) is not int:
        raise ValueError("author label does not match the effective verified GitHub account")
    return {"id": actor["id"], "login": actor["login"]}


def _assert_human_input(data: dict[str, Any], context: dict[str, Any]) -> str:
    latest = context.get("last_interaction", {})
    supplied = str(data.get("interaction_text", ""))
    if not supplied or latest.get("digest") != hashlib.sha256(supplied.encode()).hexdigest():
        raise ValueError("learning requires the actual latest provider-captured human response")
    answers = data.get("answers", [])
    if (
        not isinstance(answers, list)
        or not answers
        or any(not isinstance(a, str) or a not in supplied for a in answers)
    ):
        raise ValueError("learning answers must be verbatim excerpts of that human response")
    verification = str(data.get("verification_response", ""))
    if not verification or verification not in supplied:
        raise ValueError("practical verification must be recorded in the actual human response")
    return str(latest["reference"])


def _human_sources(data: dict[str, Any], context: dict[str, Any]) -> tuple[str, list[str], str]:
    """Preserve correct earlier answers when the human supplies a focused correction."""
    supplied = data.get("interactions")
    if supplied is None:
        reference = _assert_human_input(data, context)
        return reference, [reference] * len(data["answers"]), reference
    if not isinstance(supplied, list) or not supplied:
        raise ValueError("human source interactions are required")
    captured = {item["reference"]: item["digest"] for item in context.get("interactions", [])}
    verified: list[tuple[str, str]] = []
    for item in supplied:
        text, reference = str(item.get("text", "")), str(item.get("reference", ""))
        if not text or captured.get(reference) != hashlib.sha256(text.encode()).hexdigest():
            raise ValueError("answer source does not match a captured human interaction")
        verified.append((text, reference))

    def source(excerpt: str) -> str:
        for text, reference in reversed(verified):
            if excerpt and excerpt in text:
                return reference
        raise ValueError("answer or practical verification was not supplied by the human")

    references = [source(str(answer)) for answer in data["answers"]]
    verification = source(str(data.get("verification_response", "")))
    binding = hashlib.sha256(json.dumps([references, verification]).encode()).hexdigest()
    return "human-interactions:" + binding, references, verification


def _run_publication(
    service: Any, task: str, root: Path, context: dict[str, Any], args: argparse.Namespace
) -> dict[str, Any]:
    repository = repository_identity(root)
    if repository != context["repository"]:
        raise ValueError("repository identity changed")
    branch = _git(root, "branch", "--show-current")
    if not branch.startswith("task/") or branch.startswith("-"):
        raise ValueError("publication requires the current dedicated task branch")
    action = {"commit": "commit", "push": "push", "pr": "author_pr"}[args.command]
    binding: dict[str, Any] = service.guard_action(task, action, repository=root)
    if args.command == "commit":
        if not args.message_file:
            raise ValueError("commit requires --message-file")
        subprocess.run(
            ["git", "commit", "--file", str(args.message_file.resolve())], cwd=root, check=True
        )
        service.guard_action(task, "push", repository=root)
        event = EventType.COMMIT_CREATED
    elif args.command == "push":
        subprocess.run(["git", "push", "--", "origin", branch], cwd=root, check=True)
        remote = _git(root, "ls-remote", "--heads", "origin", "refs/heads/" + branch)
        if not remote or remote.split()[0] != _git(root, "rev-parse", "HEAD"):
            raise ValueError("push read-back did not match the covered commit")
        event = EventType.PUSH_ATTEMPTED
    else:
        if not args.body_file or not args.title or not args.base:
            raise ValueError("PR requires --body-file, --title and --base")
        policy = author_policy(root)
        record = service.show(task)
        if not record.plan or args.base != record.plan.integration_base:
            raise ValueError("PR base does not match the explicitly approved plan")
        if args.base not in policy["author_task_bases"]:
            raise ValueError("PR base is outside the repository promotion policy")
        body = args.body_file.read_text()
        template = (root / ".github" / "pull_request_template.md").read_text()
        headings = re.findall(r"^## .+$", template, re.MULTILINE)
        if not headings or any(heading not in body for heading in headings):
            raise ValueError("PR body must populate the actual author template")
        if "PENDING" not in body:
            raise ValueError("new PR must identify independent review as PENDING")
        if binding["digest"] not in body or record.plan_digest() not in body:
            raise ValueError(
                "PR evidence must identify the covered change and approved plan digests"
            )
        if re.search(
            r"/(?:Users|home|private|var/folders)/|gh[pousr]_[A-Za-z0-9]+|sk-[A-Za-z0-9]+", body
        ):
            raise ValueError("PR evidence contains a private path or possible secret")
        from harness.ai_review.session import GhTransport

        transport = GhTransport()
        actor = transport.identity()
        if actor.get("type") != "User" or actor.get("login") != context.get("human"):
            raise ValueError(
                "effective PR actor differs from recorded human; do not switch accounts"
            )
        command = [
            "gh",
            "pr",
            "create",
            "--repo",
            "github.com/" + repository,
            "--base",
            args.base,
            "--head",
            branch,
            "--title",
            args.title,
            "--body-file",
            str(args.body_file.resolve()),
            "--draft",
        ]
        number = getattr(args, "number", None)
        if number is not None:
            if number < 1:
                raise ValueError("PR number must be positive")
            pr = transport.request("GET", f"repos/{repository}/pulls/{number}")
            if (
                pr.get("head", {}).get("sha") != _git(root, "rev-parse", "HEAD")
                or pr.get("head", {}).get("ref") != branch
                or pr.get("base", {}).get("ref") != args.base
                or pr.get("user", {}).get("login") != context["human"]
            ):
                raise ValueError(
                    "author PR update does not match the covered repository/branch/head"
                )
            command = [
                "gh",
                "pr",
                "edit",
                str(number),
                "--repo",
                "github.com/" + repository,
                "--title",
                args.title,
                "--body-file",
                str(args.body_file.resolve()),
            ]
        result = subprocess.run(command, check=True, capture_output=True, text=True)
        event = (
            EventType.PULL_REQUEST_UPDATED if number is not None else EventType.PULL_REQUEST_OPENED
        )
        binding["url"] = result.stdout.strip()
    service.event_store.append(
        MonitoringEvent.create(
            event_type=event,
            session_id=context["session_id"],
            student_identifier=context["human"],
            repository=repository,
            branch=branch,
            task_identifier=task,
            commit_or_pr_reference=_git(root, "rev-parse", "HEAD"),
            result="completed",
            metadata={
                "provider": context["provider"],
                "action": action,
                "change_digest": binding.get("digest", ""),
            },
        )
    )
    return binding


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    parser.add_argument(
        "command",
        choices=(
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
        ),
    )
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--session", required=True)
    parser.add_argument("--provider", choices=("codex", "claude"), required=True)
    parser.add_argument("--task")
    parser.add_argument("--input", type=Path)
    parser.add_argument("--test-command")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--prepare", action="store_true")
    parser.add_argument("--path", action="append", default=[])
    parser.add_argument("--branch")
    parser.add_argument("--message-file", type=Path)
    parser.add_argument("--body-file", type=Path)
    parser.add_argument("--title")
    parser.add_argument("--base")
    parser.add_argument("--number", type=int)
    args = parser.parse_args(argv)
    root = args.root.resolve()
    try:
        directory = session_directory(root, args.session)
        service = task_service(directory)
        context = _read_context(directory)
        if context and (
            context.get("session_id") != args.session or context.get("provider") != args.provider
        ):
            raise ValueError("provider/session binding mismatch")
        task = args.task or context.get("task_id")
        data = _json_file(args.input) if args.input else {}
        if args.command == "start":
            if not task or not data:
                raise ValueError(
                    "start requires --task and --input with request, human "
                    "and semantic clarification checkpoint"
                )
            if context.get("task_id"):
                raise ValueError("session already has a task; start a fresh client context")
            scope = data.get("clarification_checkpoint", {}).get("scope")
            if scope != "in_scope":
                raise ValueError(
                    "ambiguous or out-of-scope objective cannot enter implementation intake"
                )
            repository = repository_identity(root)
            if data.get("repository") != repository:
                raise ValueError("expected repository does not match origin")
            human = str(data.get("human", "")).strip()
            if not human:
                raise ValueError(
                    "recorded human identifier is required; it is attribution, not role authority"
                )
            actor = author_identity(human)
            service.event_store.append(
                MonitoringEvent.create(
                    event_type=EventType.SCOPE_CHECK_PASSED,
                    session_id=args.session,
                    repository=repository,
                    task_identifier=task,
                    result="in_scope",
                    metadata={"provider": args.provider, "rationale": data.get("scope_reason", "")},
                )
            )
            record = service.start(
                repository=root,
                request=str(data.get("request", "")),
                task_id=task,
                student_identifier=human,
                repository_role="local",
                session_id=args.session,
                provider=args.provider,
                planned_files=tuple(data.get("planned_files", [])),
                clarification_checkpoint=data["clarification_checkpoint"],
            )
            if actor:
                record.inspection["github_actor"] = actor
                service.store.save(record)
            service.event_store.append(
                MonitoringEvent.create(
                    event_type=EventType.AUTHOR_IDENTITY_CHECKED,
                    session_id=args.session,
                    repository=repository,
                    task_identifier=task,
                    result="verified" if actor else "unverified",
                    metadata={"provider": args.provider, "actor": actor},
                )
            )
            context.update(
                {
                    "provider": args.provider,
                    "session_id": args.session,
                    "task_id": task,
                    "human": human,
                    "repository": repository,
                    "github_actor": actor,
                }
            )
            _write_context(directory, context)
            output: Any = record.to_dict()
        elif not task:
            raise ValueError("no current task; run start after read-only inspection")
        elif args.command == "status":
            output = service.show(task).to_dict()
        elif args.command == "propose":
            integration_base = data["plan"].get("integration_base")
            if integration_base not in author_policy(root)["author_task_bases"]:
                raise ValueError("plan must name a permitted task-to-integration PR base")
            record = service.register_plan(
                task,
                specification=TaskSpecification.from_dict(data["specification"]),
                plan=ImplementationPlan.from_dict(data["plan"]),
            )
            context["pending_plan"] = record.plan_digest()
            _write_context(directory, context)
            output = {
                "plan_digest": record.plan_digest(),
                "revision": record.revision,
                "plan": record.to_dict(),
                "next": (
                    "Show this exact plan and wait for the contextual human response; "
                    "no confirm CLI bypass exists."
                ),
            }
        elif args.command == "checkpoint":
            record = service.show(task)
            latest = context.get("last_interaction", {})
            answer = str(data.get("interaction_text", ""))
            if not answer or hashlib.sha256(answer.encode()).hexdigest() != latest.get("digest"):
                raise ValueError("clarification must match the captured human response")
            output = service.answer(
                task, answer, student_identifier=record.student_identifier
            ).to_dict()
        elif args.command == "branch":
            service.require_implementation_unlock(task)
            if not args.branch or not re.fullmatch(
                r"task/[A-Za-z0-9][A-Za-z0-9._/-]*", args.branch
            ):
                raise ValueError("branch must be an explicit task/* name")
            subprocess.run(["git", "switch", "-c", args.branch], cwd=root, check=True)
            output = service.begin_implementation(task).to_dict()
        elif args.command == "begin":
            service.require_implementation_unlock(task)
            output = service.begin_implementation(task).to_dict()
        elif args.command == "request-owner-approval":
            record = service.show(task)
            if not record.plan or not record.required_approval_groups:
                raise ValueError("there is no protected pending plan requiring owner approval")
            if not record.inspection.get("github_actor"):
                raise ValueError(
                    "original author identity is unverified; role approval stays blocked"
                )
            teams = author_policy(root).get("preimplementation_approval_teams", {})
            if any(group not in teams for group in record.required_approval_groups):
                raise ValueError("required approval role has no verified repository team mapping")
            context["pending_owner"] = {
                "digest": record.plan_digest(),
                "groups": record.required_approval_groups,
            }
            _write_context(directory, context)
            output = {
                "plan_digest": record.plan_digest(),
                "groups": record.required_approval_groups,
                "next": (
                    "A different authorized human must inspect this exact plan and agree. "
                    "The next native response verifies their effective GitHub identity "
                    "and active team membership. No account switch is automated."
                ),
            }
        elif args.command == "request-mentor-override":
            record = service.show(task)
            if record.state is not TaskState.AWAITING_LEARNING_REVIEW:
                raise ValueError(
                    "validate and inspect the complete diff before a learning override"
                )
            if not str(data.get("reason", "")).strip():
                raise ValueError("an explicit reason for the existing exception is required")
            actor = verified_mentor(root, context["human"], context.get("github_actor"))
            context["pending_override"] = {
                "digest": service.snapshot(task, repository=root)["digest"],
                "reason": data["reason"],
            }
            _write_context(directory, context)
            output = {
                "eligible_mentor": actor["login"],
                "snapshot": context["pending_override"]["digest"],
                "next": (
                    "Show the complete diff and reason. Ask whether the human uses the existing "
                    "learning-only exception for this snapshot; wait for the native response."
                ),
            }
        elif args.command == "resume":
            output = service.resume_implementation(
                task, reason=str(data.get("reason", ""))
            ).to_dict()
        elif args.command == "revise":
            output = service.correct_plan(
                task,
                correction=str(data.get("correction", "")),
                add_files=tuple(data.get("add_files", [])),
                remove_files=tuple(data.get("remove_files", [])),
                acceptance_criteria=tuple(data.get("acceptance_criteria", [])),
            ).to_dict()
            context.pop("pending_plan", None)
            _write_context(directory, context)
        elif args.command == "validation":
            if not args.test_command or not args.output:
                raise ValueError("validation requires --test-command and external --output")
            output = service.run_test(
                task,
                repository=root,
                command=args.test_command,
                output_path=args.output,
                working_tree=True,
            )
        elif args.command == "learning":
            if args.prepare:
                output = service.prepare_learning_snapshot(
                    task,
                    repository=root,
                    test_commands=data["test_commands"],
                    test_evidence=data["test_evidence"],
                ).to_dict()
            else:
                reference, answer_sources, verification_source = _human_sources(data, context)
                output = service.complete_snapshot_learning(
                    task,
                    repository=root,
                    answers=data["answers"],
                    evaluations=data["evaluations"],
                    interaction_reference=reference,
                    respondent_identifier=context["human"],
                    verification_response=data["verification_response"],
                    answer_interaction_references=answer_sources,
                    verification_interaction_reference=verification_source,
                ).to_dict()
        elif args.command == "stage":
            if not args.path:
                raise ValueError("stage requires explicit --path entries")
            for path in args.path:
                service.guard_action(task, "stage", repository=root, path=path)
            subprocess.run(["git", "add", "--", *args.path], cwd=root, check=True)
            output = {"staged": args.path}
        else:
            output = _run_publication(service, task, root, context, args)
        print(json.dumps(output, indent=2))
        return 0
    except (ValueError, OSError, RuntimeError, KeyError, subprocess.SubprocessError) as error:
        print(json.dumps({"status": "BLOCKED", "reason": str(error)}))
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
