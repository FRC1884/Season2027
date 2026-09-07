"""Executable clarification, confirmation, implementation, and learning gates."""

from __future__ import annotations

import hashlib
import re
import shlex
import subprocess
import sys
import uuid
from collections.abc import Mapping
from dataclasses import asdict
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from harness.git import (
    ChangedFile,
    diff_between,
    parse_unified_diff,
    resolve_commit,
    run_git,
)
from harness.learning_loop.reviewer import generate_questions, split_diff, validate_answer
from harness.models import RiskLevel
from harness.monitoring import EventStore, EventType, MonitoringEvent
from harness.monitoring.models import format_timestamp
from harness.policy import PolicyValidator
from harness.policy.validator import ProtectedPathRule, _matches
from harness.private_io import write_private_text
from harness.repositories.binding import WorktreeBindingError, require_worktree_binding
from harness.risk import RiskAssessment, RiskClassifier
from harness.tasks.inspection import inspect_repository
from harness.tasks.models import (
    TERMINAL_STATES,
    ClarificationQuestion,
    ImplementationPlan,
    PlanConfirmation,
    TaskRecord,
    TaskSpecification,
    TaskState,
)
from harness.tasks.snapshot import collect_snapshot
from harness.tasks.store import TaskStore


class TaskLifecycleError(ValueError):
    """Raised when a task lifecycle gate rejects an operation."""


def _now() -> str:
    return format_timestamp(datetime.now(UTC))


def _reviewer_for(risk: RiskLevel) -> str:
    if risk >= RiskLevel.HIGH:
        return "Safety Code Owners"
    if risk is RiskLevel.MEDIUM:
        return "Robot Code Owners"
    return "General Code Owners"


def _normalized_path(path: str) -> str:
    candidate = Path(path)
    if candidate.is_absolute() or ".." in candidate.parts:
        raise TaskLifecycleError("task paths must be repository-relative and cannot contain '..'")
    normalized = candidate.as_posix().removeprefix("./")
    if not normalized:
        raise TaskLifecycleError("task path cannot be empty")
    return normalized


class TaskService:
    """Own the persisted task state and enforce every implementation gate."""

    def __init__(
        self,
        *,
        store: TaskStore,
        event_store: EventStore,
        classifier: RiskClassifier | None = None,
        policy: PolicyValidator | None = None,
        reviewer_authorizations: Mapping[str, frozenset[str]] | None = None,
        bootstrap_authorities: Mapping[str, str] | None = None,
    ) -> None:
        self.store = store
        self.event_store = event_store
        self.classifier = classifier or RiskClassifier()
        self.policy = policy or PolicyValidator()
        self.reviewer_authorizations = dict(reviewer_authorizations or {})
        self.bootstrap_authorities = dict(bootstrap_authorities or {})

    def _protected_rules(self, record: TaskRecord) -> tuple[ProtectedPathRule, ...]:
        raw = record.inspection.get("protected_path_rules", [])
        restored: list[ProtectedPathRule] = []
        if isinstance(raw, list):
            for item in raw:
                if not isinstance(item, dict):
                    continue
                patterns = item.get("patterns", [])
                if not isinstance(patterns, list):
                    continue
                restored.append(
                    ProtectedPathRule(
                        identifier=str(item.get("identifier", "persisted-policy")),
                        patterns=tuple(str(pattern) for pattern in patterns),
                        required_approval=str(item.get("required_approval", "Safety Code Owners")),
                        explanation=str(
                            item.get(
                                "explanation",
                                "persisted protected path requires owner review",
                            )
                        ),
                    )
                )
        return tuple(restored) or self.policy.protected_path_rules

    def _policy_for(self, record: TaskRecord) -> PolicyValidator:
        return PolicyValidator(self._protected_rules(record))

    @staticmethod
    def _git_common_directory(repository: Path) -> Path:
        common = Path(run_git(repository, "rev-parse", "--git-common-dir"))
        if not common.is_absolute():
            common = repository / common
        return common.resolve()

    def _require_task_worktree(self, record: TaskRecord, repository: Path | str) -> Path:
        worktree = Path(repository).resolve()
        recorded = Path(record.repository).resolve()
        if self._git_common_directory(recorded) != self._git_common_directory(worktree):
            raise TaskLifecycleError("worktree does not belong to the task repository")
        confirmation = record.confirmation
        if confirmation is None:
            raise TaskLifecycleError("task has no current plan confirmation")
        base_commit = str(record.inspection.get("repository_commit", ""))
        if not base_commit:
            raise TaskLifecycleError("task inspection did not record a base commit")
        if record.repository_role == "local":
            root = Path(run_git(worktree, "rev-parse", "--show-toplevel")).resolve()
            if root != recorded or worktree != recorded:
                raise TaskLifecycleError(
                    "local session must remain in its inspected repository root"
                )
            origin = run_git(worktree, "remote", "get-url", "origin")
            if origin != record.inspection.get("local_origin"):
                raise TaskLifecycleError("local session origin changed after inspection")
            branch = run_git(worktree, "branch", "--show-current")
            if not branch.startswith("task/") or branch == record.inspection.get(
                "repository_branch"
            ):
                raise TaskLifecycleError("local session requires a dedicated task branch")
            bound_branch = record.inspection.get("local_branch")
            if bound_branch and branch != bound_branch:
                raise TaskLifecycleError(
                    "local session task branch differs from its recorded binding"
                )
            if not bound_branch and record.state is not TaskState.APPROVED_FOR_IMPLEMENTATION:
                raise TaskLifecycleError("local task branch must begin before product actions")
            try:
                run_git(worktree, "merge-base", "--is-ancestor", base_commit, "HEAD")
            except Exception as error:
                raise TaskLifecycleError(
                    "local task branch no longer descends from inspected base"
                ) from error
            return worktree
        if record.repository_role == "harness":
            root = Path(run_git(worktree, "rev-parse", "--show-toplevel")).resolve()
            if root != recorded or worktree != recorded:
                raise TaskLifecycleError(
                    "Harness implementation must remain in its recorded repository root"
                )
            branch = run_git(worktree, "branch", "--show-current")
            inspected_branch = str(record.inspection.get("repository_branch", ""))
            if not branch or branch == inspected_branch:
                raise TaskLifecycleError("Harness implementation requires a dedicated task branch")
            try:
                run_git(worktree, "merge-base", "--is-ancestor", base_commit, "HEAD")
            except Exception as error:
                raise TaskLifecycleError(
                    "Harness task branch is not descended from the inspected base"
                ) from error
            return worktree
        try:
            require_worktree_binding(
                self.event_store.read_all(),
                task_identifier=record.task_id,
                source_repository=record.repository,
                worktree=worktree,
                base_commit=base_commit,
                plan_digest=confirmation.plan_digest,
                revision=record.revision,
                confirmation_id=confirmation.confirmation_id,
            )
        except WorktreeBindingError as error:
            raise TaskLifecycleError(str(error)) from error
        return worktree

    def _event(
        self,
        record: TaskRecord,
        event_type: EventType,
        *,
        result: str = "",
        files: tuple[str, ...] = (),
        policy_status: str = "not_evaluated",
        metadata: dict[str, Any] | None = None,
        tool_or_command: str = "",
        reference: str = "",
        branch: str = "",
        reviewer_identifier: str = "",
    ) -> MonitoringEvent:
        event = MonitoringEvent.create(
            event_type=event_type,
            session_id=record.session_id,
            student_identifier=record.student_identifier,
            repository=record.repository,
            branch=branch or str(record.inspection.get("repository_branch", "")),
            task_identifier=record.task_id,
            files_affected=files,
            result=result,
            risk_level=RiskLevel.parse(record.risk_level),
            policy_status=policy_status,
            tool_or_command=tool_or_command,
            commit_or_pr_reference=reference,
            reviewer_identifier=reviewer_identifier,
            metadata={
                "repository_role": record.repository_role,
                **(
                    {"provider": record.inspection["provider"]}
                    if record.inspection.get("provider")
                    else {}
                ),
                **({} if metadata is None else metadata),
            },
        )
        self.event_store.append(event)
        return event

    def _transition(self, record: TaskRecord, state: TaskState, reason: str) -> None:
        previous = record.state
        record.transition(state, timestamp=_now(), reason=reason)
        self._event(
            record,
            EventType.TASK_STATE_CHANGED,
            result=state.value,
            metadata={"from_state": previous.value, "to_state": state.value, "reason": reason},
        )
        if state in TERMINAL_STATES:
            for authorization in record.bootstrap_authorizations:
                if authorization.get("status") != "active":
                    continue
                authorization["status"] = "expired"
                authorization["expired_at"] = _now()
                authorization["expiry_reason"] = f"task entered {state.value}"
                self._event(
                    record,
                    EventType.BOOTSTRAP_AUTHORIZATION_EXPIRED,
                    result="expired",
                    reviewer_identifier=authorization.get("actor", ""),
                    metadata={
                        "actor": authorization.get("actor", ""),
                        "role": authorization.get("role", ""),
                        "expiry_reason": authorization["expiry_reason"],
                    },
                )

    def _persist(self, record: TaskRecord) -> TaskRecord:
        record.updated_at = _now()
        self.store.save(record)
        return record

    def _request_required_approvals(self, record: TaskRecord) -> None:
        digest = record.plan_digest()
        existing = {
            (
                str(event.metadata.get("reviewer_group", "")),
                str(event.metadata.get("revision", "")),
                str(event.metadata.get("plan_digest", "")),
            )
            for event in self.event_store.read_all()
            if event.task_identifier == record.task_id
            and event.event_type is EventType.APPROVAL_REQUESTED
        }
        for group in record.required_approval_groups:
            key = (group, str(record.revision), digest)
            if key in existing:
                continue
            self._event(
                record,
                EventType.APPROVAL_REQUESTED,
                result="pending",
                metadata={
                    "reviewer_group": group,
                    "revision": record.revision,
                    "plan_digest": digest,
                },
            )

    def _checkpoint(self, record: TaskRecord, paths: list[str], checkpoint: str) -> RiskLevel:
        assessment = self.classifier.classify([ChangedFile(path=path, patch="") for path in paths])
        assessed_risk = assessment.risk
        if assessed_risk < RiskLevel.HIGH and any(
            _matches(path, rule.patterns)
            for path in paths
            for rule in self._protected_rules(record)
        ):
            assessed_risk = RiskLevel.HIGH
        previous = record.risk_level
        record.risk_level = assessed_risk.label
        record.required_reviewer_group = _reviewer_for(assessed_risk)
        record.risk_history.append(
            {
                "checkpoint": checkpoint,
                "previous": previous,
                "current": assessed_risk.label,
                "timestamp": _now(),
            }
        )
        if previous != assessed_risk.label:
            self._event(
                record,
                EventType.RISK_CLASSIFICATION_CHANGED,
                result=assessment.risk.label,
                metadata={
                    "checkpoint": checkpoint,
                    "previous": previous,
                    "current": assessed_risk.label,
                    "required_reviewer_group": record.required_reviewer_group,
                },
            )
        return assessed_risk

    def _authorize_actual_diff(
        self,
        record: TaskRecord,
        changes: tuple[ChangedFile, ...],
        *,
        checkpoint: str,
    ) -> tuple[list[str], RiskAssessment]:
        if not changes:
            raise TaskLifecycleError("implementation diff contains no changed files")
        actual = sorted({path for change in changes for path in change.affected_paths})
        planned = set(record.plan.files_to_modify if record.plan else ())
        unplanned = sorted(set(actual) - planned)
        previous_risk = RiskLevel.parse(record.risk_level)
        assessment = self.classifier.classify(changes)
        if assessment.risk < RiskLevel.HIGH and any(
            _matches(path, rule.patterns)
            for path in actual
            for rule in self._protected_rules(record)
        ):
            assessment = RiskAssessment(
                risk=RiskLevel.HIGH,
                matches=assessment.matches,
                changed_files=assessment.changed_files,
            )
        record.risk_history.append(
            {
                "checkpoint": checkpoint,
                "previous": record.risk_level,
                "current": assessment.risk.label,
                "timestamp": _now(),
            }
        )
        if not unplanned and assessment.risk <= previous_risk:
            return actual, assessment

        if unplanned:
            for path in unplanned:
                if record.plan is not None and path not in record.plan.files_to_modify:
                    record.plan.files_to_modify.append(path)
                if record.specification is not None and path not in record.specification.in_scope:
                    record.specification.in_scope.append(path)
        previous_label = record.risk_level
        if assessment.risk > previous_risk:
            record.risk_level = assessment.risk.label
            record.required_reviewer_group = _reviewer_for(assessment.risk)
        record.revision += 1
        if record.plan is not None:
            record.plan.revision = record.revision
            record.plan.expected_change_scope = "Only " + ", ".join(record.plan.files_to_modify)
        record.confirmation = None
        record.implementation_permitted = False
        if record.learning_review:
            record.learning_review["completed"] = False
            record.learning_review["invalidated_reason"] = "plan or scope changed"
        if record.implementation:
            record.implementation["tests_passed"] = False
        protected_approvals = {
            rule.required_approval
            for path in actual
            for rule in self._protected_rules(record)
            if _matches(path, rule.patterns)
        }
        if assessment.risk >= RiskLevel.HIGH:
            protected_approvals.add(_reviewer_for(assessment.risk))
        record.required_approval_groups = sorted(
            set(record.required_approval_groups) | protected_approvals
        )
        if assessment.risk > previous_risk:
            self._event(
                record,
                EventType.RISK_CLASSIFICATION_CHANGED,
                result=record.risk_level,
                metadata={
                    "checkpoint": checkpoint,
                    "previous": previous_label,
                    "current": record.risk_level,
                    "required_reviewer_group": record.required_reviewer_group,
                },
            )
        reason = (
            "actual diff expanded scope and increased risk; reconfirmation required"
            if unplanned and assessment.risk > previous_risk
            else (
                "actual diff expanded scope; reconfirmation required"
                if unplanned
                else "risk increased from actual diff; reconfirmation required"
            )
        )
        self._transition(record, TaskState.AWAITING_PLAN_CONFIRMATION, reason)
        if unplanned:
            self._event(
                record,
                EventType.SCOPE_EXPANDED,
                result=reason,
                files=tuple(unplanned),
                metadata={
                    "revision": record.revision,
                    "plan_digest": record.plan_digest(),
                },
            )
            self._event(
                record,
                EventType.POLICY_VIOLATION,
                result="implementation touched paths outside confirmed plan",
                files=tuple(unplanned),
                policy_status="violation",
                metadata={"code": "implementation-outside-confirmed-plan"},
            )
        self._event(
            record,
            EventType.PLAN_CORRECTED,
            result=reason,
            files=tuple(actual),
            metadata={
                "revision": record.revision,
                "plan_digest": record.plan_digest(),
            },
        )
        self._request_required_approvals(record)
        self._persist(record)
        raise TaskLifecycleError(reason)

    def _planned_files(self, record: TaskRecord) -> list[str]:
        selected = [str(path) for path in record.inspection.get("selected_candidate_files", [])]
        if selected:
            return list(dict.fromkeys(selected))
        relevant = [str(path) for path in record.inspection.get("relevant_files", [])]
        request = " ".join(
            [
                record.original_request,
                *[question.answer for question in record.questions if question.answer],
            ]
        ).lower()
        explicit = [
            path
            for path in relevant
            if path.lower() in request or Path(path).name.lower() in request
        ]
        java_test_mode = next(
            (path for path in relevant if path.endswith("/TestModeConfiguration.java")),
            "",
        )
        if (
            not explicit
            and java_test_mode
            and {"test", "speed", "drivetrain"} & set(re.findall(r"[a-z]+", request))
        ):
            return [java_test_mode]
        allowed = [
            path
            for path in relevant
            if not path.endswith("AGENTS.md")
            and not path.lower().endswith("readme.md")
            and "/tests/" not in f"/{path}"
            and not path.startswith("tests/")
            and not any(_matches(path, rule.patterns) for rule in self._protected_rules(record))
        ]
        return list(dict.fromkeys(explicit or allowed[:1]))

    @staticmethod
    def _targeted_java_test_commands(planned: list[str]) -> list[str]:
        commands: list[str] = []
        marker = "src/test/java/"
        for path in planned:
            if marker not in path or not path.endswith(".java"):
                continue
            class_path = path.split(marker, 1)[1].removesuffix(".java")
            class_name = class_path.replace("/", ".")
            commands.append(f'./gradlew test --tests "{class_name}"')
        return commands

    @staticmethod
    def _selected_candidate_context(
        repository: Path,
        planned_files: tuple[str, ...],
    ) -> tuple[list[str], list[str]]:
        files: list[str] = []
        symbols: list[str] = []
        marker = "src/test/java/"
        for raw_path in planned_files:
            planned = _normalized_path(raw_path)
            if marker not in planned or not planned.endswith("Test.java"):
                continue
            source = (
                "src/main/java/" + planned.split(marker, 1)[1].removesuffix("Test.java") + ".java"
            )
            source_path = repository / source
            if not source_path.is_file() or source_path.is_symlink():
                continue
            files.extend((source, planned))
            try:
                content = source_path.read_text(encoding="utf-8")
            except (OSError, UnicodeDecodeError):
                continue
            names = re.findall(
                r"(?m)^\s*(?:public|protected|private)?\s*"
                r"(?:(?:static|final|abstract|synchronized)\s+)*"
                r"(?:class|interface|enum|record|[A-Za-z0-9_<>,.?\[\]]+)\s+"
                r"([A-Za-z_][A-Za-z0-9_]*)\s*(?:\(|\{)",
                content,
            )
            class_name = Path(source).stem
            ordered_names = [name for name in names if name != class_name]
            if class_name in names:
                ordered_names.append(class_name)
            symbols.extend(f"{source}:{name}" for name in ordered_names[:12])
        return list(dict.fromkeys(files)), list(dict.fromkeys(symbols))

    def _refresh_inspection_from_answers(self, record: TaskRecord) -> None:
        combined_request = " ".join(
            [
                record.original_request,
                *[question.answer for question in record.questions if question.answer],
            ]
        )
        previous_files = {str(path) for path in record.inspection.get("relevant_files", [])}
        previous_symbols = {str(symbol) for symbol in record.inspection.get("relevant_symbols", [])}
        preserved = {
            key: record.inspection[key]
            for key in (
                "protected_path_rules",
                "selected_candidate_files",
                "local_origin",
                "provider",
            )
            if key in record.inspection
        }
        inspection = inspect_repository(record.repository, combined_request)
        if not inspection.repository_unchanged:
            raise TaskLifecycleError(
                "repository changed during adaptive read-only clarification inspection"
            )
        recorded_commit = str(record.inspection.get("repository_commit", ""))
        if recorded_commit and inspection.repository_commit != recorded_commit:
            raise TaskLifecycleError(
                "repository base changed during clarification; restart from a reviewed base"
            )
        recorded_branch = str(record.inspection.get("repository_branch", ""))
        if recorded_branch and inspection.repository_branch != recorded_branch:
            raise TaskLifecycleError("repository branch changed before plan confirmation")
        record.inspection = inspection.to_dict()
        record.inspection.update(preserved)
        selected = tuple(
            str(path) for path in record.inspection.get("selected_candidate_files", [])
        )
        if selected:
            candidate_files, candidate_symbols = self._selected_candidate_context(
                Path(record.repository),
                selected,
            )
            record.inspection["relevant_files"] = list(
                dict.fromkeys(
                    [
                        *candidate_files,
                        *[str(path) for path in record.inspection["relevant_files"]],
                    ]
                )
            )
            record.inspection["relevant_symbols"] = list(
                dict.fromkeys(
                    [
                        *candidate_symbols,
                        *[str(symbol) for symbol in record.inspection["relevant_symbols"]],
                    ]
                )
            )
        record.inspection["protected_relevant_paths"] = sorted(
            path
            for path in inspection.relevant_files
            if any(_matches(path, rule.patterns) for rule in self._protected_rules(record))
        )
        for path in inspection.relevant_files:
            if path not in previous_files:
                self._event(
                    record,
                    EventType.RELEVANT_FILE_IDENTIFIED,
                    result=path,
                    files=(path,),
                    metadata={"source": "adaptive_clarification"},
                )
        for symbol in inspection.relevant_symbols:
            if symbol not in previous_symbols:
                self._event(
                    record,
                    EventType.RELEVANT_SYMBOL_IDENTIFIED,
                    result=symbol,
                    metadata={
                        "symbol": symbol,
                        "source": "adaptive_clarification",
                    },
                )

    @staticmethod
    def _question_categories(record: TaskRecord) -> list[str]:
        request = " ".join(
            [
                record.original_request,
                *[question.answer for question in record.questions if question.answer],
            ]
        ).lower()
        answered = {question.category for question in record.questions if question.answer}
        categories: list[str] = []
        protected_requested = any(
            path.lower() in request or Path(path).name.lower() in request
            for path in record.inspection.get("protected_relevant_paths", [])
        )
        if protected_requested and "safety" not in answered:
            categories.append("safety")
        precise_expected = bool(
            re.search(r"\b(?:set|change|update|replace)\b.{0,80}\b\d+(?:\.\d+)?\b", request)
            or re.search(r"\bshould\b|\bexpected\b", request)
        )
        if not precise_expected and "expected_behaviour" not in answered:
            categories.append("expected_behaviour")
        is_bug = bool(re.search(r"\bfix\b|\bbug\b|\bbroken\b|\bincorrect\b", request))
        mode_known = bool(
            re.search(
                r"\bsim(?:ulation)?\b|\bautonomous\b|\bteleop\b|\btest[- ]?mode\b"
                r"|\bphysical hardware\b",
                request,
            )
        )
        if is_bug and not mode_known and "reproduction" not in answered:
            categories.append("reproduction")
        validation_known = bool(
            re.search(
                r"\bpytest\b|\bgradlew\b|\brun (?:the )?tests?\b|\badd (?:a )?tests?\b"
                r"|\bvalidate\b|\bverify\b|\bacceptance\b",
                request,
            )
        )
        if not validation_known and "acceptance" not in answered:
            categories.append("acceptance")
        return categories

    def _ask_next(self, record: TaskRecord) -> bool:
        categories = self._question_categories(record)
        if not categories:
            return False
        if len(record.questions) >= record.max_questions:
            record.mentor_escalation_required = True
            record.escalation_reason = "material ambiguity remains after clarification limit"
            record.inspection["confirmed_facts"] = [
                question.answer for question in record.questions if question.answer
            ]
            record.inspection["unresolved_questions"] = categories
            record.inspection["recommended_action"] = "mentor escalation"
            self._event(record, EventType.CLARIFICATION_LIMIT_REACHED, result="blocked")
            self._event(
                record,
                EventType.MENTOR_ESCALATION_REQUESTED,
                result=record.escalation_reason,
            )
            return True
        category = categories[0]
        files = [str(item) for item in record.inspection.get("relevant_files", [])]
        symbols = [str(item) for item in record.inspection.get("relevant_symbols", [])]
        primary = files[0] if files else "the repository"
        symbol = symbols[0].split(":", 1)[-1] if symbols else ""
        selected_test_files = [
            str(path)
            for path in record.inspection.get("selected_candidate_files", [])
            if str(path).endswith("Test.java")
        ]
        expected_prompt = (
            f"{primary}"
            + (f" contains `{symbol}`. " if symbol else ". ")
            + (
                f"Which current behaviour and edge cases should the planned "
                f"`{selected_test_files[0]}` document without changing production code?"
                if selected_test_files
                else "What exact behaviour or value should replace the observed behaviour?"
            )
        )
        prompts = {
            "safety": (
                f"{primary} is protected. Is the protected control actually intended to change, "
                "and which Safety Code Owner approval covers it?"
            ),
            "expected_behaviour": expected_prompt,
            "reproduction": (
                "Does the issue occur in simulation, autonomous, teleop, test mode, or on "
                "physical hardware, and what does it do now?"
            ),
            "acceptance": (
                f"How should the result be validated? The repository suggests "
                f"`{record.inspection.get('validation_commands', ['documented tests'])[0]}`."
            ),
        }
        question = ClarificationQuestion(
            identifier=f"clarification-{len(record.questions) + 1}",
            category=category,
            prompt=prompts[category],
            grounded_files=files[:3],
            grounded_symbols=symbols[:3],
        )
        record.questions.append(question)
        self._event(
            record,
            EventType.AMBIGUITY_DETECTED,
            result=category,
            metadata={"category": category},
        )
        if category == "safety":
            self._event(record, EventType.SAFETY_AMBIGUITY_DETECTED, result="blocked")
        self._event(
            record,
            EventType.CLARIFICATION_QUESTION_ASKED,
            result=question.prompt,
            files=tuple(question.grounded_files),
            metadata={"question_id": question.identifier, "category": category},
        )
        return True

    def start(
        self,
        *,
        repository: Path | str,
        request: str,
        task_id: str | None = None,
        student_identifier: str = "anonymous-student",
        max_questions: int = 4,
        repository_role: str = "target",
        planned_files: tuple[str, ...] = (),
        clarification_checkpoint: dict[str, Any] | None = None,
        session_id: str | None = None,
        provider: str = "",
    ) -> TaskRecord:
        if provider not in {"", "codex", "claude"}:
            raise TaskLifecycleError("provider must be codex, claude, or empty for legacy records")
        if session_id is not None and not session_id.strip():
            raise TaskLifecycleError("an explicitly supplied provider session_id cannot be blank")
        if clarification_checkpoint is not None:
            checkpoint = clarification_checkpoint
            if (
                checkpoint.get("scope") != "in_scope"
                or not str(checkpoint.get("confirmed_understanding", "")).strip()
                or not str(checkpoint.get("interaction_reference", "")).strip()
                or not isinstance(checkpoint.get("assumptions"), list)
                or not isinstance(checkpoint.get("unresolved"), list)
            ):
                raise TaskLifecycleError("semantic scope and clarification checkpoint incomplete")
            for item in checkpoint["unresolved"]:
                if not isinstance(item, dict) or not all(
                    str(item.get(key, "")).strip() for key in ("id", "prompt", "category")
                ):
                    raise TaskLifecycleError("unresolved questions need id, prompt and category")
        if not request.strip():
            raise TaskLifecycleError("student request is required")
        if max_questions < 1:
            raise TaskLifecycleError("max question count must be positive")
        if repository_role not in {"harness", "target", "local"}:
            raise TaskLifecycleError("repository role must be harness, target, or local")
        repo = Path(repository).resolve()
        local_origin = ""
        if repository_role == "local":
            if Path(run_git(repo, "rev-parse", "--show-toplevel")).resolve() != repo:
                raise TaskLifecycleError("local session intake requires the exact repository root")
            local_origin = run_git(repo, "remote", "get-url", "origin")
            if not local_origin:
                raise TaskLifecycleError("local session requires an inspected origin")
        task_directory = self.store.directory.resolve()
        event_path = self.event_store.path.resolve()
        if task_directory.is_relative_to(repo) or event_path.is_relative_to(repo):
            raise TaskLifecycleError(
                "task artifacts and monitoring events must be stored outside "
                "the inspected repository"
            )
        identifier = task_id or f"TASK-{uuid.uuid4().hex[:10].upper()}"
        if self.store.exists(identifier):
            existing = self.store.load(identifier)
            if (
                Path(existing.repository).resolve() == repo
                and existing.original_request == request.strip()
                and existing.student_identifier == student_identifier
                and existing.repository_role == repository_role
                and (session_id is None or existing.session_id == session_id)
                and existing.inspection.get("provider", "") == provider
            ):
                return existing
            raise TaskLifecycleError(
                f"task identifier already exists with different task data: {identifier}"
            )
        timestamp = _now()
        record = TaskRecord(
            task_id=identifier,
            original_request=request.strip(),
            repository=str(repo),
            student_identifier=student_identifier,
            session_id=session_id or f"task-{identifier}",
            inspection={"provider": provider} if provider else {},
            repository_role=repository_role,
            created_at=timestamp,
            updated_at=timestamp,
            max_questions=max_questions,
        )
        self._event(record, EventType.STUDENT_REQUEST_SUBMITTED, result=request.strip())
        self._transition(record, TaskState.INSPECTING, "repository inspection started")
        self._event(record, EventType.REPOSITORY_INSPECTION_STARTED, result="read-only")
        inspection = inspect_repository(repo, request)
        if not inspection.repository_unchanged:
            raise TaskLifecycleError("repository changed during read-only inspection")
        record.inspection = inspection.to_dict()
        if provider:
            record.inspection["provider"] = provider
        if local_origin:
            record.inspection["local_origin"] = local_origin
        record.inspection["protected_path_rules"] = [
            {
                "identifier": rule.identifier,
                "patterns": list(rule.patterns),
                "required_approval": rule.required_approval,
                "explanation": rule.explanation,
            }
            for rule in self.policy.protected_path_rules
        ]
        if planned_files:
            normalized_planned = [_normalized_path(path) for path in planned_files]
            record.inspection["selected_candidate_files"] = normalized_planned
            selected_files, selected_symbols = self._selected_candidate_context(
                repo,
                tuple(normalized_planned),
            )
            record.inspection["relevant_files"] = list(
                dict.fromkeys(
                    [
                        *selected_files,
                        *[str(path) for path in record.inspection["relevant_files"]],
                    ]
                )
            )
            record.inspection["relevant_symbols"] = list(
                dict.fromkeys(
                    [
                        *selected_symbols,
                        *[str(symbol) for symbol in record.inspection["relevant_symbols"]],
                    ]
                )
            )
        for path in inspection.relevant_files:
            self._event(
                record,
                EventType.RELEVANT_FILE_IDENTIFIED,
                result=path,
                files=(path,),
            )
        for symbol in inspection.relevant_symbols:
            self._event(
                record,
                EventType.RELEVANT_SYMBOL_IDENTIFIED,
                result=symbol,
                metadata={"symbol": symbol},
            )
        planned = self._planned_files(record)
        self._checkpoint(record, planned, "initial_repository_inspection")
        if clarification_checkpoint is not None:
            record.inspection["clarification_checkpoint"] = clarification_checkpoint
            record.questions = [
                ClarificationQuestion(
                    identifier=item["id"],
                    category=item["category"],
                    prompt=item["prompt"],
                    grounded_files=list(record.inspection.get("relevant_files", [])),
                )
                for item in clarification_checkpoint["unresolved"]
            ]
            if record.questions:
                self._transition(
                    record, TaskState.AWAITING_CLARIFICATION, "semantic ambiguity remains"
                )
                for question in record.questions:
                    self._event(
                        record,
                        EventType.CLARIFICATION_QUESTION_ASKED,
                        result=question.prompt,
                        metadata={"question_id": question.identifier},
                    )
            else:
                self._prepare_specification(record)
        elif self._ask_next(record):
            self._transition(record, TaskState.AWAITING_CLARIFICATION, "material ambiguity remains")
        else:
            self._prepare_specification(record)
        return self._persist(record)

    @staticmethod
    def _normalize_test_command(command: str) -> tuple[str, ...]:
        try:
            tokens = shlex.split(command)
        except ValueError as error:
            raise TaskLifecycleError(f"invalid test command: {error}") from error
        if not tokens:
            raise TaskLifecycleError("test command cannot be empty")
        executable = Path(tokens[0]).name
        if executable.startswith("python"):
            tokens[0] = "python"
            if tokens[1:3] != ["-m", "pytest"]:
                raise TaskLifecycleError("only the pytest Python module is allowed")
        elif tokens[0] == "./gradlew":
            if any("deploy" in token.lower() for token in tokens[1:]):
                raise TaskLifecycleError("deployment Gradle tasks are never allowed")
        elif tokens[0] in {
            "./scripts/format",
            "./scripts/lint",
            "./scripts/typecheck",
            "./scripts/test",
            "./scripts/verify",
        }:
            if len(tokens) != 1:
                raise TaskLifecycleError(
                    "repository validation scripts do not accept extra arguments"
                )
        else:
            raise TaskLifecycleError(
                "test command must use pytest, Gradle, or a known repository validation script"
            )
        return tuple(tokens)

    def run_test(
        self,
        task_id: str,
        *,
        repository: Path | str,
        command: str,
        output_path: Path | str,
        timeout_seconds: int = 600,
        working_tree: bool = False,
    ) -> dict[str, Any]:
        record = self.store.load(task_id)
        if record.state is not TaskState.IMPLEMENTING:
            raise TaskLifecycleError("tests can run only while implementation is active")
        if not self._confirmation_valid(record):
            raise TaskLifecycleError("test execution requires a current plan confirmation")
        if timeout_seconds < 1 or timeout_seconds > 3600:
            raise TaskLifecycleError("test timeout must be between 1 and 3600 seconds")
        repo = self._require_task_worktree(record, repository)
        normalized = self._normalize_test_command(command)
        planned = {
            self._normalize_test_command(item)
            for item in (record.plan.validation_commands if record.plan else [])
        }
        if normalized not in planned:
            raise TaskLifecycleError("test command is not in the confirmed implementation plan")
        status_before = run_git(
            repo,
            "status",
            "--porcelain=v1",
            "--untracked-files=all",
        )
        snapshot_before = self.snapshot(task_id, repository=repo) if working_tree else None
        if status_before and not working_tree:
            raise TaskLifecycleError(
                "test execution requires a clean committed implementation worktree"
            )
        branch = run_git(repo, "branch", "--show-current")
        initial_branch = str(record.inspection.get("repository_branch", ""))
        if not branch or (initial_branch and branch == initial_branch):
            raise TaskLifecycleError("test execution requires the dedicated implementation branch")
        base_commit = str(record.inspection.get("repository_commit", ""))
        if not base_commit:
            raise TaskLifecycleError("task inspection did not record a base commit")
        commit = resolve_commit(repo, "HEAD")
        changes = (
            collect_snapshot(repo, base_commit)[1]
            if working_tree
            else parse_unified_diff(diff_between(repo, base_commit, commit))
        )
        self._authorize_actual_diff(
            record,
            changes,
            checkpoint="pre_test_implementation_diff",
        )
        evidence = Path(output_path).expanduser().resolve()
        if evidence == repo or evidence.is_relative_to(repo):
            raise TaskLifecycleError("test evidence must be stored outside the target repository")
        started = self._event(
            record,
            EventType.TEST_STARTED,
            result="started",
            tool_or_command=command,
            reference=commit,
            branch=branch,
            metadata={"timeout_seconds": timeout_seconds},
        )
        try:
            execution_tokens = shlex.split(command)
            if Path(execution_tokens[0]).name.startswith("python"):
                execution_tokens[0] = sys.executable
            completed = subprocess.run(
                execution_tokens,
                cwd=repo,
                check=False,
                capture_output=True,
                text=True,
                timeout=timeout_seconds,
            )
            transcript = completed.stdout
            if completed.stderr:
                transcript += "\n--- stderr ---\n" + completed.stderr
            write_private_text(evidence, transcript)
            status = run_git(
                repo,
                "status",
                "--porcelain=v1",
                "--untracked-files=all",
            )
            unchanged = (
                collect_snapshot(repo, base_commit)[0]["digest"] == snapshot_before["digest"]
                if snapshot_before is not None
                else not status
            )
            passed = completed.returncode == 0 and unchanged
            digest = hashlib.sha256(evidence.read_bytes()).hexdigest()
            result = "passed" if passed else "failed"
            completed_event = self._event(
                record,
                EventType.TEST_COMPLETED,
                result=result,
                policy_status="passed" if passed else "violation",
                tool_or_command=command,
                reference=commit,
                branch=branch,
                metadata={
                    "exit_status": completed.returncode,
                    "evidence_path": str(evidence),
                    "evidence_sha256": digest,
                    "start_event_id": started.event_id,
                    "worktree_status_after": status,
                    "snapshot_digest": snapshot_before["digest"] if snapshot_before else "",
                    "plan_digest": record.plan_digest(),
                },
            )
            if not unchanged:
                self._event(
                    record,
                    EventType.POLICY_VIOLATION,
                    result="validation command changed the target worktree",
                    policy_status="violation",
                    metadata={
                        "code": "test-command-modified-worktree",
                        "status": status,
                    },
                    branch=branch,
                )
            return {
                "passed": passed,
                "command": command,
                "exit_status": completed.returncode,
                "commit": commit,
                "branch": branch,
                "evidence_path": str(evidence),
                "evidence_sha256": digest,
                "event_id": completed_event.event_id,
                "worktree_status_after": status,
                "snapshot_digest": snapshot_before["digest"] if snapshot_before else "",
            }
        except subprocess.TimeoutExpired as error:
            write_private_text(
                evidence,
                f"command timed out after {timeout_seconds} seconds\n",
            )
            self._event(
                record,
                EventType.TEST_COMPLETED,
                result="failed",
                policy_status="violation",
                tool_or_command=command,
                reference=commit,
                branch=branch,
                metadata={
                    "timeout_seconds": timeout_seconds,
                    "evidence_path": str(evidence),
                    "start_event_id": started.event_id,
                },
            )
            raise TaskLifecycleError("test command timed out") from error

    def _prepare_specification(self, record: TaskRecord) -> None:
        answers = {
            question.category: question.answer for question in record.questions if question.answer
        }
        planned = self._planned_files(record)
        self._checkpoint(record, planned, "after_student_clarification")
        expected = answers.get("expected_behaviour", record.original_request)
        acceptance = answers.get("acceptance", "")
        commands = [
            *self._targeted_java_test_commands(planned),
            *[str(item) for item in record.inspection.get("validation_commands", [])],
        ]
        commands = list(dict.fromkeys(commands))
        relevant = [str(item) for item in record.inspection.get("relevant_files", [])]
        protected = [str(item) for item in record.inspection.get("protected_relevant_paths", [])]
        symbols = [str(item) for item in record.inspection.get("relevant_symbols", [])]
        subsystem = Path(planned[0]).parent.as_posix() if planned else "repository"
        required_approval_groups = sorted(
            {
                rule.required_approval
                for path in planned
                for rule in self._protected_rules(record)
                if _matches(path, rule.patterns)
            }
        )
        record.required_approval_groups = required_approval_groups
        record.specification = TaskSpecification(
            task_id=record.task_id,
            original_request=record.original_request,
            clarified_problem_statement=(f"{record.original_request} Expected result: {expected}"),
            expected_behaviour=expected,
            reproduction_steps=[
                answers.get(
                    "reproduction",
                    "Use the mode and conditions named in the original student request.",
                )
            ],
            affected_subsystem=subsystem,
            relevant_files=relevant,
            relevant_symbols=symbols,
            in_scope=planned,
            out_of_scope=[
                *protected,
                "unrelated refactors, policy weakening, deployment, and competition-mode changes",
            ],
            acceptance_criteria=[
                acceptance
                or (
                    "The requested behaviour is observed and "
                    f"`{commands[0] if commands else 'tests'}` passes."
                ),
                "No path outside the confirmed plan changes.",
            ],
            required_tests=commands,
            safety_constraints=[
                "Protected safety limits and policy controls remain unchanged.",
                "Any protected-path work requires its independent reviewer approval.",
            ],
            protected_paths_unchanged=protected,
            initial_risk_classification=record.risk_level,
            required_reviewer_group=record.required_reviewer_group,
            student_answers=[
                {
                    "question_id": question.identifier,
                    "category": question.category,
                    "answer": question.answer,
                }
                for question in record.questions
                if question.answer
            ],
        )
        tests = [
            path
            for path in relevant
            if path.startswith(("tests/", "src/test/"))
            or "/tests/" in f"/{path}"
            or "/src/test/" in f"/{path}"
        ]
        record.plan = ImplementationPlan(
            revision=record.revision,
            implementation_steps=[
                "Inspect the confirmed files and their existing tests.",
                "Make the smallest change inside the confirmed scope envelope.",
                "Run each confirmed validation command against the uncommitted snapshot.",
                "Review the complete staged, unstaged, and intended new-file diff.",
                "Evaluate the student's diff-grounded answers before committing.",
                "Commit only the validated and learned snapshot on the dedicated branch.",
            ],
            files_to_inspect=relevant,
            files_to_modify=planned,
            behavioural_changes=[expected],
            tests_to_add_or_update=tests[:4],
            validation_commands=commands,
            safety_checks=[
                "Run protected-path policy validation against the actual diff.",
                "Verify protected paths listed in the specification are unchanged.",
                "Reclassify risk from the actual implementation diff.",
            ],
            expected_change_scope=f"Only {', '.join(planned) or 'the confirmed files'}",
            potential_failure_modes=[
                "The change does not reproduce the expected behaviour.",
                "The actual diff expands beyond the confirmed scope.",
                "A protected limit is changed or bypassed.",
            ],
            rollback_strategy="Revert only the task diff to the recorded pre-change commit.",
            planned_verification_challenge=(
                "Identify the exact changed line or condition and predict the relevant test result."
            ),
        )
        self._transition(record, TaskState.SPECIFICATION_READY, "clarification complete")
        self._checkpoint(record, planned, "task_specification_generated")
        self._event(
            record,
            EventType.TASK_SPECIFICATION_GENERATED,
            result="generated",
            files=tuple(relevant),
            metadata={"revision": record.revision},
        )
        self._event(
            record,
            EventType.IMPLEMENTATION_PLAN_GENERATED,
            result="generated",
            files=tuple(planned),
            metadata={"revision": record.revision},
        )
        self._request_required_approvals(record)
        self._transition(
            record, TaskState.AWAITING_PLAN_CONFIRMATION, "student confirmation required"
        )

    def show(self, task_id: str) -> TaskRecord:
        return self.store.load(task_id)

    def answer(
        self, task_id: str, answer: str, *, student_identifier: str | None = None
    ) -> TaskRecord:
        record = self.store.load(task_id)
        if record.state is not TaskState.AWAITING_CLARIFICATION:
            raise TaskLifecycleError("task is not awaiting clarification")
        if student_identifier and student_identifier != record.student_identifier:
            raise TaskLifecycleError("clarification answer must come from the recorded student")
        words = re.findall(r"[A-Za-z0-9_.-]+", answer)
        if len(words) < 2 or answer.strip().lower() in {"yes", "no", "ok", "i understand"}:
            raise TaskLifecycleError("clarification answer must provide concrete task information")
        question = record.current_question
        if question is None:
            raise TaskLifecycleError("task has no unanswered clarification question")
        question.answer = answer.strip()
        question.answered_at = _now()
        self._event(
            record,
            EventType.CLARIFICATION_ANSWER_RECORDED,
            result=answer.strip(),
            metadata={"question_id": question.identifier, "category": question.category},
        )
        if "clarification_checkpoint" in record.inspection:
            if record.current_question is None:
                self._prepare_specification(record)
            return self._persist(record)
        self._refresh_inspection_from_answers(record)
        self._checkpoint(
            record,
            self._planned_files(record),
            f"clarification_answer_{len([item for item in record.questions if item.answer])}",
        )
        if not self._ask_next(record):
            self._prepare_specification(record)
        return self._persist(record)

    def _confirmation_valid(self, record: TaskRecord) -> bool:
        confirmation = record.confirmation
        if confirmation is None or confirmation.revision != record.revision:
            return False
        if confirmation.student_identifier != record.student_identifier:
            return False
        if confirmation.plan_digest != record.plan_digest():
            return False
        events = self.event_store.read_all()
        confirmed = any(
            event.event_id == confirmation.confirmation_event_id
            and event.event_type is EventType.PLAN_CONFIRMED
            and event.task_identifier == record.task_id
            and event.metadata.get("confirmation_id") == confirmation.confirmation_id
            and event.metadata.get("plan_digest") == confirmation.plan_digest
            for event in events
        )
        unlocked = any(
            event.event_id == confirmation.unlock_event_id
            and event.event_type is EventType.IMPLEMENTATION_UNLOCKED
            and event.task_identifier == record.task_id
            and event.metadata.get("confirmation_id") == confirmation.confirmation_id
            and event.metadata.get("plan_digest") == confirmation.plan_digest
            for event in events
        )
        return confirmed and unlocked

    @staticmethod
    def _current_approval_groups(record: TaskRecord) -> set[str]:
        digest = record.plan_digest()
        return {
            str(approval.get("reviewer_group", ""))
            for approval in record.approvals
            if str(approval.get("revision", "")) == str(record.revision)
            and str(approval.get("plan_digest", "")) == digest
            and str(approval.get("reviewer_identifier", "")).strip()
        }

    def _current_bootstrap_authorization(self, record: TaskRecord) -> bool:
        expected_actor = self.bootstrap_authorities.get(record.task_id, "")
        if not expected_actor:
            return False
        digest = record.plan_digest()
        return any(
            authorization.get("task_identifier") == record.task_id
            and authorization.get("actor") == expected_actor
            and authorization.get("role") == "bootstrap_organization_owner"
            and authorization.get("revision") == str(record.revision)
            and authorization.get("plan_digest") == digest
            and authorization.get("temporary") == "true"
            and authorization.get("inherits_to_later_tasks") == "false"
            and authorization.get("status") == "active"
            for authorization in record.bootstrap_authorizations
        )

    @staticmethod
    def _plan_artifacts_complete(record: TaskRecord) -> bool:
        specification = record.specification
        plan = record.plan
        if specification is None or plan is None:
            return False
        inspection_complete = all(
            str(record.inspection.get(key, "")).strip()
            for key in ("repository_branch", "repository_commit")
        )
        return all(
            (
                inspection_complete,
                specification.task_id == record.task_id,
                bool(specification.original_request.strip()),
                bool(specification.clarified_problem_statement.strip()),
                bool(specification.expected_behaviour.strip()),
                bool(specification.in_scope),
                bool(specification.acceptance_criteria),
                bool(specification.required_tests),
                bool(specification.safety_constraints),
                plan.revision == record.revision,
                bool(plan.implementation_steps),
                bool(plan.files_to_modify),
                bool(plan.behavioural_changes),
                bool(plan.validation_commands),
                bool(plan.safety_checks),
                bool(plan.expected_change_scope.strip()),
                bool(plan.rollback_strategy.strip()),
                bool(plan.planned_verification_challenge.strip()),
            )
        )

    def confirm(self, task_id: str, *, student_identifier: str | None = None) -> TaskRecord:
        record = self.store.load(task_id)
        if record.state is not TaskState.AWAITING_PLAN_CONFIRMATION:
            raise TaskLifecycleError("task is not awaiting plan confirmation")
        if not self._plan_artifacts_complete(record):
            record.confirmation = None
            record.implementation_permitted = False
            record.mentor_escalation_required = True
            record.escalation_reason = (
                "persisted task lacks a complete current specification or implementation plan"
            )
            self._transition(
                record,
                TaskState.AWAITING_CLARIFICATION,
                "incomplete migrated planning artifacts require regeneration",
            )
            self._event(
                record,
                EventType.POLICY_VIOLATION,
                result="plan confirmation blocked for incomplete planning artifacts",
                policy_status="violation",
            )
            self._persist(record)
            raise TaskLifecycleError(
                "task specification and implementation plan are incomplete; "
                "clarification or mentor-assisted regeneration is required"
            )
        student = student_identifier or record.student_identifier
        if student != record.student_identifier:
            raise TaskLifecycleError("plan confirmation must come from the recorded student")
        missing = sorted(
            set(record.required_approval_groups) - self._current_approval_groups(record)
        )
        if missing and self._current_bootstrap_authorization(record):
            missing = []
        if missing:
            raise TaskLifecycleError(
                "student confirmation cannot replace required approval from: " + ", ".join(missing)
            )
        confirmation_id = str(uuid.uuid4())
        digest = record.plan_digest()
        metadata = {
            "confirmation_id": confirmation_id,
            "plan_digest": digest,
            "revision": record.revision,
            "student_identifier": student,
        }
        confirmed = self._event(
            record, EventType.PLAN_CONFIRMED, result="confirmed", metadata=metadata
        )
        unlocked = self._event(
            record, EventType.IMPLEMENTATION_UNLOCKED, result="unlocked", metadata=metadata
        )
        record.confirmation = PlanConfirmation(
            confirmation_id=confirmation_id,
            student_identifier=student,
            confirmed_at=_now(),
            revision=record.revision,
            plan_digest=digest,
            confirmation_event_id=confirmed.event_id,
            unlock_event_id=unlocked.event_id,
        )
        record.implementation_permitted = True
        self._transition(
            record,
            TaskState.APPROVED_FOR_IMPLEMENTATION,
            "student confirmed current plan revision",
        )
        return self._persist(record)

    def check_edit(self, task_id: str, path: str) -> dict[str, Any]:
        record = self.store.load(task_id)
        normalized = _normalized_path(path)
        valid = self._confirmation_valid(record)
        allowed_state = record.state in {
            TaskState.APPROVED_FOR_IMPLEMENTATION,
            TaskState.IMPLEMENTING,
        }
        plan_paths = tuple(record.plan.files_to_modify if record.plan else ())
        edit_authority_groups = self._current_approval_groups(record)
        if self._current_bootstrap_authorization(record):
            # The task-scoped bootstrap exception authorizes implementation of
            # the already confirmed protected scope. It is never persisted as
            # a reviewer approval and cannot satisfy later Harness review.
            edit_authority_groups.update(record.required_approval_groups)
        report = self._policy_for(record).authorize_task_edit(
            normalized,
            implementation_unlocked=valid and allowed_state,
            planned_paths=plan_paths,
            approval_groups=frozenset(edit_authority_groups),
        )
        if not report.passed:
            self._event(
                record,
                EventType.POLICY_VIOLATION,
                result="edit blocked",
                files=(normalized,),
                policy_status="violation",
                metadata={"violations": [item.to_dict() for item in report.violations]},
            )
        return {
            "task_id": task_id,
            "path": normalized,
            "allowed": report.passed,
            "violations": [item.to_dict() for item in report.violations],
        }

    def begin_implementation(self, task_id: str, files: list[str] | None = None) -> TaskRecord:
        record = self.require_implementation_unlock(task_id)
        if record.repository_role == "local":
            repo = self._require_task_worktree(record, record.repository)
            record.inspection["local_branch"] = run_git(repo, "branch", "--show-current")
        planned = list(record.plan.files_to_modify if record.plan else ())
        for path in files or planned:
            permission = self.check_edit(task_id, path)
            if not permission["allowed"]:
                raise TaskLifecycleError(f"implementation is not authorized for {path}")
        self._transition(record, TaskState.IMPLEMENTING, "implementation gate passed")
        assert record.confirmation is not None
        self._event(
            record,
            EventType.IMPLEMENTATION_STARTED,
            result="started",
            files=tuple(files or planned),
            metadata={
                "confirmation_id": record.confirmation.confirmation_id,
                "plan_digest": record.confirmation.plan_digest,
                "revision": record.revision,
                "bootstrap_authorization_applied": self._current_bootstrap_authorization(record),
            },
        )
        return self._persist(record)

    def require_implementation_unlock(self, task_id: str) -> TaskRecord:
        """Return a task only when persisted state and append-only events agree."""

        record = self.store.load(task_id)
        if record.state is not TaskState.APPROVED_FOR_IMPLEMENTATION:
            raise TaskLifecycleError("task is not approved for implementation")
        if not record.implementation_permitted or not self._confirmation_valid(record):
            record.implementation_permitted = False
            self._persist(record)
            raise TaskLifecycleError(
                "implementation lock rejected missing, stale, or tampered plan confirmation"
            )
        return record

    def confirmed_scope(self, task_id: str) -> tuple[TaskRecord, tuple[str, ...], str, str]:
        """Return the immutable scope inputs bound to the current student confirmation."""

        record = self.store.load(task_id)
        if record.state not in {
            TaskState.APPROVED_FOR_IMPLEMENTATION,
            TaskState.IMPLEMENTING,
            TaskState.IMPLEMENTATION_COMPLETE,
            TaskState.AWAITING_LEARNING_REVIEW,
            TaskState.AWAITING_MENTOR_REVIEW,
            TaskState.APPROVED,
        }:
            raise TaskLifecycleError("task has no active confirmed implementation scope")
        if not self._confirmation_valid(record) or record.plan is None:
            raise TaskLifecycleError("confirmed implementation scope is missing or stale")
        confirmation = record.confirmation
        if confirmation is None:
            raise TaskLifecycleError("confirmed implementation scope is missing or stale")
        base_commit = str(record.inspection.get("repository_commit", ""))
        if not base_commit:
            raise TaskLifecycleError("task inspection did not record a base commit")
        return (
            record,
            tuple(record.plan.files_to_modify),
            base_commit,
            confirmation.plan_digest,
        )

    def expand_scope(self, task_id: str, path: str, reason: str) -> TaskRecord:
        record = self.store.load(task_id)
        if record.state not in {
            TaskState.AWAITING_PLAN_CONFIRMATION,
            TaskState.APPROVED_FOR_IMPLEMENTATION,
            TaskState.IMPLEMENTING,
        }:
            raise TaskLifecycleError("scope can only change before implementation completes")
        if not reason.strip():
            raise TaskLifecycleError("scope expansion reason is required")
        normalized = _normalized_path(path)
        if record.plan is None or record.specification is None:
            raise TaskLifecycleError("task has no plan to amend")
        if normalized not in record.plan.files_to_modify:
            record.plan.files_to_modify.append(normalized)
            record.specification.in_scope.append(normalized)
        record.revision += 1
        record.plan.revision = record.revision
        record.plan.expected_change_scope = "Only " + ", ".join(record.plan.files_to_modify)
        record.confirmation = None
        record.implementation_permitted = False
        if record.learning_review:
            record.learning_review["completed"] = False
            record.learning_review["invalidated_reason"] = "plan or scope changed"
        if record.implementation:
            record.implementation["tests_passed"] = False
        required = {
            rule.required_approval
            for rule in self._protected_rules(record)
            if _matches(normalized, rule.patterns)
        }
        record.required_approval_groups = sorted(set(record.required_approval_groups) | required)
        self._checkpoint(record, record.plan.files_to_modify, "scope_expansion")
        if record.state is not TaskState.AWAITING_PLAN_CONFIRMATION:
            self._transition(
                record,
                TaskState.AWAITING_PLAN_CONFIRMATION,
                "scope or risk changed; reconfirmation required",
            )
        self._event(
            record,
            EventType.SCOPE_EXPANDED,
            result=reason.strip(),
            files=(normalized,),
            metadata={"revision": record.revision},
        )
        self._event(
            record,
            EventType.PLAN_CORRECTED,
            result="reconfirmation required",
            files=(normalized,),
            metadata={
                "revision": record.revision,
                "plan_digest": record.plan_digest(),
            },
        )
        self._request_required_approvals(record)
        return self._persist(record)

    def correct_plan(
        self,
        task_id: str,
        *,
        correction: str = "",
        add_files: tuple[str, ...] = (),
        remove_files: tuple[str, ...] = (),
        acceptance_criteria: tuple[str, ...] = (),
    ) -> TaskRecord:
        record = self.store.load(task_id)
        if record.state not in {
            TaskState.AWAITING_PLAN_CONFIRMATION,
            TaskState.APPROVED_FOR_IMPLEMENTATION,
            TaskState.IMPLEMENTING,
        }:
            raise TaskLifecycleError("only a current implementation plan can be corrected")
        if record.plan is None or record.specification is None:
            raise TaskLifecycleError("task has no plan to correct")
        if not any(
            (
                correction.strip(),
                add_files,
                remove_files,
                acceptance_criteria,
            )
        ):
            raise TaskLifecycleError("plan correction must change scope, behaviour, or acceptance")
        normalized_additions = [_normalized_path(path) for path in add_files]
        normalized_removals = {_normalized_path(path) for path in remove_files}
        files = [path for path in record.plan.files_to_modify if path not in normalized_removals]
        for path in normalized_additions:
            if path not in files:
                files.append(path)
        if not files:
            raise TaskLifecycleError("corrected plan must retain at least one file to modify")
        record.plan.files_to_modify = files
        record.specification.in_scope = list(files)
        if correction.strip():
            record.specification.expected_behaviour = correction.strip()
            record.plan.behavioural_changes = [correction.strip()]
        record.specification.acceptance_criteria.extend(
            criterion.strip()
            for criterion in acceptance_criteria
            if criterion.strip()
            and criterion.strip() not in record.specification.acceptance_criteria
        )
        record.revision += 1
        record.plan.revision = record.revision
        record.plan.expected_change_scope = "Only " + ", ".join(files)
        record.required_approval_groups = sorted(
            {
                rule.required_approval
                for path in files
                for rule in self._protected_rules(record)
                if _matches(path, rule.patterns)
            }
        )
        record.confirmation = None
        record.implementation_permitted = False
        if record.learning_review:
            record.learning_review["completed"] = False
            record.learning_review["invalidated_reason"] = "plan or scope changed"
        if record.implementation:
            record.implementation["tests_passed"] = False
        self._checkpoint(record, files, "plan_correction")
        if record.state is not TaskState.AWAITING_PLAN_CONFIRMATION:
            self._transition(
                record,
                TaskState.AWAITING_PLAN_CONFIRMATION,
                "student corrected or narrowed the plan",
            )
        self._event(
            record,
            EventType.PLAN_CORRECTED,
            result=correction.strip() or "scope or acceptance corrected",
            files=tuple(files),
            metadata={
                "revision": record.revision,
                "plan_digest": record.plan_digest(),
            },
        )
        self._request_required_approvals(record)
        return self._persist(record)

    def record_approval(
        self, task_id: str, reviewer_identifier: str, reviewer_group: str
    ) -> TaskRecord:
        record = self.store.load(task_id)
        if record.state is not TaskState.AWAITING_PLAN_CONFIRMATION:
            raise TaskLifecycleError(
                "reviewer approval is only valid for the current confirmable plan"
            )
        if not reviewer_identifier.strip():
            raise TaskLifecycleError("reviewer identifier is required")
        if reviewer_identifier.strip() == record.student_identifier:
            raise TaskLifecycleError("the recorded student cannot approve a protected plan")
        authorized_groups = self.reviewer_authorizations.get(
            reviewer_identifier.strip(), frozenset()
        )
        if reviewer_group not in authorized_groups:
            raise TaskLifecycleError("reviewer is not authorized for the requested reviewer group")
        if reviewer_group not in record.required_approval_groups:
            raise TaskLifecycleError("reviewer group is not required by the current plan")
        digest = record.plan_digest()
        record.approvals.append(
            {
                "reviewer_identifier": reviewer_identifier.strip(),
                "reviewer_group": reviewer_group,
                "recorded_at": _now(),
                "revision": str(record.revision),
                "plan_digest": digest,
            }
        )
        self._event(
            record,
            EventType.REVIEWER_APPROVAL_RECORDED,
            result="approved",
            reviewer_identifier=reviewer_identifier.strip(),
            metadata={
                "reviewer_identifier": reviewer_identifier.strip(),
                "reviewer_group": reviewer_group,
                "revision": record.revision,
                "plan_digest": digest,
            },
        )
        return self._persist(record)

    def record_bootstrap_authorization(
        self,
        task_id: str,
        actor: str,
        *,
        authority_basis: str,
    ) -> TaskRecord:
        record = self.store.load(task_id)
        if record.state is not TaskState.AWAITING_PLAN_CONFIRMATION:
            raise TaskLifecycleError(
                "bootstrap authorization is only valid for the current confirmable plan"
            )
        expected_actor = self.bootstrap_authorities.get(task_id, "")
        if not expected_actor or actor.strip() != expected_actor:
            raise TaskLifecycleError(
                "actor is not the configured bootstrap organization owner for this task"
            )
        if not authority_basis.strip():
            raise TaskLifecycleError("bootstrap authority basis is required")
        digest = record.plan_digest()
        authorization = {
            "task_identifier": task_id,
            "actor": actor.strip(),
            "role": "bootstrap_organization_owner",
            "authority_basis": authority_basis.strip(),
            "recorded_at": _now(),
            "revision": str(record.revision),
            "plan_digest": digest,
            "temporary": "true",
            "inherits_to_later_tasks": "false",
            "status": "active",
            "expiry_condition": ("task closed or replacement draft PR merged or abandoned"),
        }
        record.bootstrap_authorizations.append(authorization)
        self._event(
            record,
            EventType.BOOTSTRAP_AUTHORIZATION_RECORDED,
            result="authorized",
            reviewer_identifier=actor.strip(),
            metadata={
                "actor": actor.strip(),
                "role": "bootstrap_organization_owner",
                "revision": record.revision,
                "plan_digest": digest,
                "temporary": True,
                "inherits_to_later_tasks": False,
                "expiry_condition": authorization["expiry_condition"],
            },
        )
        return self._persist(record)

    def cancel(self, task_id: str, reason: str) -> TaskRecord:
        record = self.store.load(task_id)
        if not reason.strip():
            raise TaskLifecycleError("cancellation reason is required")
        record.confirmation = None
        record.implementation_permitted = False
        if record.learning_review:
            record.learning_review["completed"] = False
            record.learning_review["invalidated_reason"] = "plan or scope changed"
        if record.implementation:
            record.implementation["tests_passed"] = False
        self._transition(record, TaskState.CANCELLED, reason.strip())
        self._event(record, EventType.PLAN_REJECTED, result=reason.strip())
        return self._persist(record)

    def escalate(self, task_id: str, reason: str) -> TaskRecord:
        record = self.store.load(task_id)
        if not reason.strip():
            raise TaskLifecycleError("mentor escalation reason is required")
        record.mentor_escalation_required = True
        record.escalation_reason = reason.strip()
        self._event(
            record,
            EventType.MENTOR_ESCALATION_REQUESTED,
            result=reason.strip(),
            metadata={"state": record.state.value},
        )
        return self._persist(record)

    def complete_implementation(
        self,
        task_id: str,
        *,
        repository: Path | str,
        test_commands: list[str],
        test_evidence: list[str],
        base_reference: str,
        head_reference: str,
    ) -> TaskRecord:
        record = self.store.load(task_id)
        if record.state is not TaskState.IMPLEMENTING:
            raise TaskLifecycleError("task implementation has not started")
        if not self._confirmation_valid(record):
            raise TaskLifecycleError("implementation confirmation is stale or invalid")
        worktree = self._require_task_worktree(record, repository)
        status = run_git(
            worktree,
            "status",
            "--porcelain=v1",
            "--untracked-files=all",
        )
        if status:
            raise TaskLifecycleError(
                "implementation completion requires a clean worktree; commit or "
                "remove all staged, unstaged, and untracked task changes"
            )
        base_commit = resolve_commit(worktree, base_reference)
        head_commit = resolve_commit(worktree, head_reference)
        if resolve_commit(worktree, "HEAD") != head_commit:
            raise TaskLifecycleError(
                "implementation head reference must be the checked-out worktree HEAD"
            )
        recorded_base = str(record.inspection.get("repository_commit", ""))
        if recorded_base and base_commit != recorded_base:
            raise TaskLifecycleError(
                "implementation base does not match the repository commit inspected "
                "before plan confirmation"
            )
        initial_branch = str(record.inspection.get("repository_branch", ""))
        current_branch = run_git(worktree, "branch", "--show-current")
        if not current_branch:
            raise TaskLifecycleError("implementation worktree must be on a named branch")
        if initial_branch and current_branch == initial_branch:
            raise TaskLifecycleError(
                "implementation must use a dedicated branch, not the inspected base branch"
            )
        changes = parse_unified_diff(diff_between(worktree, base_commit, head_commit))
        if not changes:
            raise TaskLifecycleError("implementation diff contains no changed files")
        if not test_commands or not test_evidence:
            raise TaskLifecycleError(
                "implementation completion requires passing test commands and evidence"
            )
        if len(test_commands) != len(test_evidence):
            raise TaskLifecycleError("each test command requires exactly one evidence file")
        missing_evidence = [
            reference for reference in test_evidence if not Path(reference).expanduser().is_file()
        ]
        if missing_evidence:
            raise TaskLifecycleError(
                "test evidence files do not exist: " + ", ".join(missing_evidence)
            )
        evidence_records = [
            {
                "path": str(Path(reference).expanduser().resolve()),
                "sha256": hashlib.sha256(Path(reference).expanduser().read_bytes()).hexdigest(),
            }
            for reference in test_evidence
        ]
        events = self.event_store.read_all()
        for command, evidence in zip(test_commands, evidence_records, strict=True):
            matching = [
                event
                for event in events
                if event.task_identifier == record.task_id
                and event.event_type is EventType.TEST_COMPLETED
                and event.result == "passed"
                and event.tool_or_command == command
                and event.commit_or_pr_reference == head_commit
                and event.branch == current_branch
                and event.metadata.get("evidence_path") == evidence["path"]
                and event.metadata.get("evidence_sha256") == evidence["sha256"]
            ]
            if not matching:
                raise TaskLifecycleError(
                    "test evidence is not bound to a successful harness execution "
                    f"for the implementation commit: {command}"
                )
        actual, assessment = self._authorize_actual_diff(
            record,
            changes,
            checkpoint="implementation_diff",
        )
        sections = split_diff(changes)
        questions = generate_questions(sections)
        challenge_text = (
            "Explain the safety guard and demonstrate the rollback procedure."
            if assessment.risk >= RiskLevel.HIGH
            else (
                record.plan.planned_verification_challenge
                if record.plan is not None
                else "Identify the exact changed line and predict the relevant test result."
            )
        )
        record.implementation = {
            "changed_files": actual,
            "test_commands": test_commands,
            "test_evidence": evidence_records,
            "tests_passed": True,
            "base_reference": base_commit,
            "head_reference": head_commit,
            "branch": current_branch,
            "risk_classification": assessment.risk.label,
            "risk_matches": [match.to_dict() for match in assessment.matches],
        }
        record.learning_review = {
            "questions": [asdict(question) for question in questions],
            "answers": [],
            "practical_challenge": {
                "challenge": challenge_text,
                "student_response": "",
                "evidence_references": [],
                "passed": False,
                "mentor_escalation_required": False,
            },
            "completed": False,
        }
        self._transition(
            record, TaskState.IMPLEMENTATION_COMPLETE, "implementation and tests recorded"
        )
        self._event(
            record,
            EventType.IMPLEMENTATION_COMPLETED,
            result="completed",
            files=tuple(actual),
            metadata={"tests": test_commands, "evidence": evidence_records},
        )
        self._transition(
            record,
            TaskState.AWAITING_LEARNING_REVIEW,
            "diff-specific student review required",
        )
        for question in questions:
            self._event(
                record,
                EventType.POST_IMPLEMENTATION_QUESTION_ASKED,
                result=question.prompt,
                files=(question.file,),
                metadata={"question_id": question.identifier},
            )
        self._event(
            record,
            EventType.VERIFICATION_CHALLENGE_ASSIGNED,
            result=challenge_text,
        )
        self._event(record, EventType.LEARNING_LOOP_STARTED, result="started")
        return self._persist(record)

    def complete_learning(
        self,
        task_id: str,
        answers: list[str],
        *,
        student_response: str,
        evidence_references: list[str],
        passed: bool,
        respondent_identifier: str | None = None,
        respondent_role: str = "student",
    ) -> TaskRecord:
        record = self.store.load(task_id)
        if record.state is not TaskState.AWAITING_LEARNING_REVIEW:
            raise TaskLifecycleError("task is not awaiting the student learning review")
        respondent = respondent_identifier or record.student_identifier
        if respondent_role != "student" or respondent != record.student_identifier:
            self._event(
                record,
                EventType.LEARNING_REVIEW_FAILED,
                result="agent or non-student self-answering rejected",
                policy_status="violation",
            )
            raise TaskLifecycleError("post-implementation answers must come from the student")
        questions = record.learning_review.get("questions", [])
        if len(answers) != len(questions):
            raise TaskLifecycleError(
                f"expected {len(questions)} post-implementation answers, received {len(answers)}"
            )
        for answer in answers:
            validate_answer(answer)
        if not student_response.strip() or not evidence_references:
            raise TaskLifecycleError(
                "practical verification requires a student response and evidence"
            )
        missing_evidence = [
            reference
            for reference in evidence_references
            if not Path(reference).expanduser().is_file()
        ]
        if missing_evidence:
            raise TaskLifecycleError(
                "verification evidence files do not exist: " + ", ".join(missing_evidence)
            )
        verification_evidence = [
            {
                "path": str(Path(reference).expanduser().resolve()),
                "sha256": hashlib.sha256(Path(reference).expanduser().read_bytes()).hexdigest(),
            }
            for reference in evidence_references
        ]
        implementation_commit = str(record.implementation.get("head_reference", ""))
        implementation_branch = str(record.implementation.get("branch", ""))
        if not implementation_commit or not implementation_branch:
            raise TaskLifecycleError(
                "practical verification requires a recorded implementation commit and branch"
            )
        events = self.event_store.read_all()
        binding_events = [
            event
            for event in events
            if event.task_identifier == record.task_id
            and event.event_type is EventType.DEMO_WORKTREE_CREATED
            and str(event.metadata.get("plan_digest", "")) == record.plan_digest()
            and str(event.metadata.get("revision", "")) == str(record.revision)
        ]
        if not binding_events:
            raise TaskLifecycleError(
                "practical verification requires the recorded isolated worktree"
            )
        worktree_path = Path(str(binding_events[-1].metadata.get("worktree_path", "")))
        worktree = self._require_task_worktree(record, worktree_path)
        if resolve_commit(worktree, "HEAD") != implementation_commit:
            raise TaskLifecycleError(
                "practical verification evidence is stale for the current worktree HEAD"
            )
        bound_evidence: list[dict[str, str]] = []
        for evidence in verification_evidence:
            matching = [
                event
                for event in events
                if event.task_identifier == record.task_id
                and event.session_id == record.session_id
                and event.event_type is EventType.TEST_COMPLETED
                and event.commit_or_pr_reference == implementation_commit
                and event.branch == implementation_branch
                and event.metadata.get("evidence_path") == evidence["path"]
                and event.metadata.get("evidence_sha256") == evidence["sha256"]
                and (not passed or event.result == "passed")
            ]
            if not matching:
                raise TaskLifecycleError(
                    "practical verification evidence is not execution-backed for "
                    "the current task and implementation commit"
                )
            event = matching[-1]
            bound_evidence.append(
                {
                    **evidence,
                    "test_event_id": event.event_id,
                    "command": event.tool_or_command,
                    "commit": event.commit_or_pr_reference,
                }
            )
        record.learning_review["answers"] = [
            {
                "question_id": str(question.get("identifier", "")),
                "answer": answer,
                "student_identifier": respondent,
            }
            for question, answer in zip(questions, answers, strict=True)
        ]
        for item in record.learning_review["answers"]:
            self._event(
                record,
                EventType.STUDENT_ANSWER_RECORDED,
                result=str(item["answer"]),
                metadata={"question_id": item["question_id"]},
            )
        challenge = record.learning_review["practical_challenge"]
        challenge.update(
            {
                "student_response": student_response.strip(),
                "evidence_references": bound_evidence,
                "passed": passed,
                "mentor_escalation_required": not passed,
            }
        )
        self._event(
            record,
            EventType.VERIFICATION_EVIDENCE_RECORDED,
            result="passed" if passed else "failed",
            metadata={
                "student_response": student_response.strip(),
                "evidence_references": bound_evidence,
                "challenge": str(challenge.get("challenge", "")),
                "implementation_commit": implementation_commit,
            },
        )
        if not passed:
            record.mentor_escalation_required = True
            record.escalation_reason = "practical verification challenge failed"
            self._event(
                record,
                EventType.LEARNING_REVIEW_FAILED,
                result=record.escalation_reason,
            )
            return self._persist(record)
        record.learning_review["completed"] = True
        self._event(record, EventType.LEARNING_LOOP_COMPLETED, result="completed")
        self._event(record, EventType.LEARNING_REVIEW_COMPLETED, result="completed")
        self._transition(record, TaskState.AWAITING_MENTOR_REVIEW, "student learning review passed")
        return self._persist(record)

    def register_plan(
        self,
        task_id: str,
        *,
        specification: TaskSpecification,
        plan: ImplementationPlan,
    ) -> TaskRecord:
        """Replace a pending generated draft with the exact inspected human-facing plan."""
        record = self.store.load(task_id)
        if record.state is not TaskState.AWAITING_PLAN_CONFIRMATION or record.confirmation:
            raise TaskLifecycleError("register a plan only while awaiting explicit confirmation")
        if record.current_question or record.mentor_escalation_required:
            raise TaskLifecycleError("unresolved clarification blocks plan registration")
        plan.files_to_modify = [_normalized_path(path) for path in plan.files_to_modify]
        if set(specification.in_scope) != set(plan.files_to_modify):
            raise TaskLifecycleError("specification and plan scopes differ")
        if set(specification.required_tests) != set(plan.validation_commands):
            raise TaskLifecycleError("specification and plan validation differ")
        if specification.original_request != record.original_request:
            raise TaskLifecycleError("specification must preserve the actual user request")
        risk = RiskLevel.parse(specification.initial_risk_classification)
        if risk < RiskLevel.parse(record.risk_level):
            raise TaskLifecycleError("registered plan cannot silently lower inspected risk")
        record.specification, record.plan = specification, plan
        record.risk_level = risk.label
        record.required_reviewer_group = _reviewer_for(risk)
        record.required_approval_groups = sorted(
            set(record.required_approval_groups)
            | {
                rule.required_approval
                for path in plan.files_to_modify
                for rule in self._protected_rules(record)
                if _matches(path, rule.patterns)
            }
        )
        if not self._plan_artifacts_complete(record):
            raise TaskLifecycleError("registered plan is incomplete or uses the wrong revision")
        self._event(
            record,
            EventType.IMPLEMENTATION_PLAN_GENERATED,
            result="exact plan registered",
            files=tuple(plan.files_to_modify),
            metadata={"plan_digest": record.plan_digest(), "revision": record.revision},
        )
        return self._persist(record)

    def confirm_plan_response(
        self,
        task_id: str,
        *,
        response: str,
        interaction_reference: str,
        plan_digest: str,
        student_identifier: str | None = None,
    ) -> TaskRecord:
        """Record an explicit contextual answer, not a tool permission or clarification.

        The caller must supply a reference to the actual human interaction. This
        editable local reference provides traceability, not host authentication.
        """
        record = self.store.load(task_id)
        if (
            not interaction_reference.strip()
            or plan_digest != record.plan_digest()
            or response.strip().lower().rstrip(".! ")
            not in {
                "yes",
                "yes i approve",
                "go ahead",
                "approved",
                "looks good",
                "continue",
                "do it",
            }
        ):
            self._event(
                record,
                EventType.POLICY_VIOLATION,
                result="explicit current-plan approval missing",
                policy_status="violation",
                metadata={"action": "plan_approval"},
            )
            raise TaskLifecycleError(
                "explicit approval of the current plan and interaction reference required"
            )
        record = self.confirm(task_id, student_identifier=student_identifier)
        record.inspection["plan_interaction"] = {
            "reference": interaction_reference,
            "plan_digest": plan_digest,
            "response": response,
            "student_identifier": record.student_identifier,
        }
        return self._persist(record)

    def snapshot(self, task_id: str, *, repository: Path | str) -> dict[str, Any]:
        """Inspect every tracked and nonignored new file without modifying the index."""
        record = self.store.load(task_id)
        if not self._confirmation_valid(record):
            raise TaskLifecycleError("snapshot requires current plan confirmation")
        repo = self._require_task_worktree(record, repository)
        snapshot, changes = collect_snapshot(repo, str(record.inspection["repository_commit"]))
        snapshot.update(
            {
                "plan_digest": record.plan_digest(),
                "revision": record.revision,
                "repository": str(repo),
                "task_id": record.task_id,
                "session_id": record.session_id,
            }
        )
        if changes:
            self._authorize_actual_diff(record, changes, checkpoint="working_tree_snapshot")
        return snapshot

    def _invalidate_snapshot(self, record: TaskRecord, reason: str) -> None:
        record.learning_review["completed"] = False
        record.learning_review["invalidated_reason"] = reason
        record.implementation["tests_passed"] = False
        if record.state in {TaskState.AWAITING_LEARNING_REVIEW, TaskState.AWAITING_MENTOR_REVIEW}:
            self._transition(record, TaskState.IMPLEMENTING, reason)
        self._event(
            record,
            EventType.LEARNING_REVIEW_FAILED,
            result=reason,
            metadata={"action": "learning_invalidated"},
        )
        self._persist(record)

    def resume_implementation(self, task_id: str, *, reason: str) -> TaskRecord:
        """Invalidate downstream evidence before further edits inside the approved plan."""
        record = self.store.load(task_id)
        if (
            record.state
            not in {TaskState.AWAITING_LEARNING_REVIEW, TaskState.AWAITING_MENTOR_REVIEW}
            or not self._confirmation_valid(record)
            or not reason.strip()
        ):
            raise TaskLifecycleError("resuming edits requires current plan and a recorded reason")
        self._invalidate_snapshot(record, reason)
        return self.store.load(task_id)

    def prepare_learning_snapshot(
        self,
        task_id: str,
        *,
        repository: Path | str,
        test_commands: list[str],
        test_evidence: list[str],
    ) -> TaskRecord:
        """Validate an uncommitted change and generate questions before any commit."""
        record = self.store.load(task_id)
        if record.state is not TaskState.IMPLEMENTING:
            raise TaskLifecycleError("snapshot learning requires active implementation")
        snapshot = self.snapshot(task_id, repository=repository)
        if not snapshot["changed_files"]:
            raise TaskLifecycleError("implementation diff contains no changed files")
        if not test_commands or len(test_commands) != len(test_evidence):
            raise TaskLifecycleError("each validation command requires execution evidence")
        planned = set(record.plan.validation_commands if record.plan else [])
        if set(test_commands) != planned:
            raise TaskLifecycleError("all current-plan validation commands are required")
        events = self.event_store.read_all()
        evidence_records = []
        for command, reference in zip(test_commands, test_evidence, strict=True):
            path = Path(reference).expanduser().resolve()
            if path.is_relative_to(Path(repository).resolve()) or not path.is_file():
                raise TaskLifecycleError("validation evidence must exist outside the checkout")
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            matching = [
                event
                for event in events
                if event.task_identifier == task_id
                and event.session_id == record.session_id
                and event.event_type is EventType.TEST_COMPLETED
                and event.result == "passed"
                and event.branch == snapshot["branch"]
                and event.tool_or_command == command
                and event.metadata.get("snapshot_digest") == snapshot["digest"]
                and event.metadata.get("plan_digest") == record.plan_digest()
                and event.metadata.get("evidence_path") == str(path)
                and event.metadata.get("evidence_sha256") == digest
            ]
            if not matching:
                raise TaskLifecycleError(
                    "validation is not execution-backed for the current snapshot"
                )
            evidence_records.append(
                {"path": str(path), "sha256": digest, "event_id": matching[-1].event_id}
            )
        changes = collect_snapshot(Path(repository), snapshot["base_reference"])[1]
        questions = generate_questions(split_diff(changes))
        record.implementation = {
            **snapshot,
            "snapshot": snapshot,
            "tests_passed": True,
            "test_commands": test_commands,
            "test_evidence": evidence_records,
        }
        record.learning_review = {
            "questions": [asdict(q) for q in questions],
            "answers": [],
            "completed": False,
        }
        self._transition(
            record, TaskState.IMPLEMENTATION_COMPLETE, "uncommitted validation snapshot recorded"
        )
        self._transition(
            record, TaskState.AWAITING_LEARNING_REVIEW, "human diff-grounded answers required"
        )
        for question in questions:
            self._event(
                record,
                EventType.POST_IMPLEMENTATION_QUESTION_ASKED,
                result=question.prompt,
                files=(question.file,),
                metadata={
                    "question_id": question.identifier,
                    "snapshot_digest": snapshot["digest"],
                },
            )
        self._event(
            record,
            EventType.LEARNING_LOOP_STARTED,
            result="snapshot learning requested",
            metadata={"snapshot_digest": snapshot["digest"]},
        )
        return self._persist(record)

    def complete_snapshot_learning(
        self,
        task_id: str,
        *,
        repository: Path | str,
        answers: list[str],
        evaluations: list[dict[str, Any]],
        interaction_reference: str,
        respondent_identifier: str,
        verification_response: str,
        answer_interaction_references: list[str] | None = None,
        verification_interaction_reference: str | None = None,
    ) -> TaskRecord:
        """Record human answers plus diff-grounded semantic assessments.

        Deterministic code verifies completeness and bindings; it does not claim
        to infer understanding or authenticate a human from editable records.
        """
        record = self.store.load(task_id)
        if record.state is not TaskState.AWAITING_LEARNING_REVIEW:
            raise TaskLifecycleError("task is not awaiting snapshot learning")
        snapshot = self.snapshot(task_id, repository=repository)
        expected = record.implementation.get("snapshot", {})
        if snapshot != expected:
            self._invalidate_snapshot(record, "change snapshot changed before learning")
            raise TaskLifecycleError("learning snapshot is stale; validate the updated diff")
        if (
            respondent_identifier != record.student_identifier
            or not interaction_reference.strip()
            or not verification_response.strip()
        ):
            raise TaskLifecycleError(
                "actual student interaction and verification response required"
            )
        questions = record.learning_review.get("questions", [])
        if not questions or len(answers) != len(questions) or len(evaluations) != len(questions):
            raise TaskLifecycleError(
                "each question requires a human answer and semantic evaluation"
            )
        answer_refs = (
            answer_interaction_references
            if answer_interaction_references is not None
            else [interaction_reference] * len(answers)
        )
        verification_ref = (
            verification_interaction_reference
            if verification_interaction_reference is not None
            else interaction_reference
        )
        if (
            len(answer_refs) != len(answers)
            or any(not isinstance(ref, str) or not ref.strip() for ref in answer_refs)
            or not verification_ref.strip()
        ):
            raise TaskLifecycleError(
                "each human answer and verification requires its interaction reference"
            )
        items = []
        for question, answer, evaluation, answer_ref in zip(
            questions, answers, evaluations, answer_refs, strict=True
        ):
            validate_answer(answer)
            if (
                evaluation.get("question_id") != question["identifier"]
                or not all(
                    isinstance(evaluation.get(key), str) and evaluation[key].strip()
                    for key in ("rationale", "evaluator", "evidence_reference")
                )
                or type(evaluation.get("correct")) is not bool
                or evaluation.get("answer_digest") != hashlib.sha256(answer.encode()).hexdigest()
                or evaluation.get("snapshot_digest") != snapshot["digest"]
                or evaluation.get("interaction_reference") != answer_ref
            ):
                raise TaskLifecycleError(
                    "semantic evaluation requires question, answer/snapshot binding, "
                    "judgment and provenance"
                )
            items.append(
                {
                    "question_id": question["identifier"],
                    "answer": answer,
                    "evaluation": evaluation,
                    "interaction_reference": answer_ref,
                    "student_identifier": respondent_identifier,
                    "snapshot_digest": snapshot["digest"],
                }
            )
        record.learning_review.update(
            {
                "answers": items,
                "interaction_reference": interaction_reference,
                "verification_response": verification_response,
                "verification_interaction_reference": verification_ref,
                "snapshot_digest": snapshot["digest"],
                "plan_digest": record.plan_digest(),
                "changed_files": snapshot["changed_files"],
            }
        )
        if not all(item["evaluation"]["correct"] for item in items):
            self._event(
                record,
                EventType.LEARNING_REVIEW_FAILED,
                result="incorrect or incomplete human answer",
                metadata={"snapshot_digest": snapshot["digest"]},
            )
            self._persist(record)
            raise TaskLifecycleError("incorrect learning answers require focused follow-up")
        record.learning_review["completed"] = True
        self._transition(
            record, TaskState.AWAITING_MENTOR_REVIEW, "evaluated snapshot learning passed"
        )
        self._event(
            record,
            EventType.LEARNING_REVIEW_COMPLETED,
            result="passed",
            metadata={
                "snapshot_digest": snapshot["digest"],
                "plan_digest": record.plan_digest(),
                "interaction_reference": interaction_reference,
            },
        )
        return self._persist(record)

    def record_mentor_learning_override(
        self,
        task_id: str,
        *,
        repository: Path | str,
        verified_login: str,
        verified_repository: str,
        membership_state: str,
        git_attribution_present: bool,
        interaction_reference: str,
        reason: str,
    ) -> TaskRecord:
        """Preserve Season2027's existing, explicitly invoked mentor learning exception.

        The native caller must freshly verify the effective GitHub human and
        active mentors membership, and capture the actual waiver interaction.
        These inputs and local records provide provenance, not cryptographic
        identity proof. They grant no owner/safety approval or merge authority.
        The shared harness repository has no corresponding learning exception.
        """
        record = self.store.load(task_id)
        if record.state is not TaskState.AWAITING_LEARNING_REVIEW:
            raise TaskLifecycleError(
                "mentor learning override requires prepared validated learning"
            )
        repo = Path(repository).resolve()
        origin = run_git(repo, "remote", "get-url", "origin")
        target_origin = re.fullmatch(
            r"(?:https://github\.com/|git@github\.com:|ssh://git@github\.com/)"
            r"FRC1884/Season2027(?:\.git)?/?",
            origin,
            re.IGNORECASE,
        )
        if (
            not target_origin
            or verified_repository.casefold() != "frc1884/season2027"
            or record.repository_role == "harness"
        ):
            raise TaskLifecycleError(
                "mentor learning exception is restricted to verified FRC1884/Season2027"
            )
        if (
            not verified_login
            or verified_login != record.student_identifier
            or verified_login.lower().endswith("[bot]")
            or membership_state != "active"
            or git_attribution_present is not True
            or not interaction_reference.strip()
            or not reason.strip()
        ):
            raise TaskLifecycleError(
                "verified active mentor, Git attribution and explicit human waiver required"
            )
        if not run_git(repo, "config", "user.name") or not run_git(repo, "config", "user.email"):
            raise TaskLifecycleError("mentor learning override requires configured Git attribution")
        snapshot = self.snapshot(task_id, repository=repo)
        if snapshot != record.implementation.get("snapshot", {}) or not record.implementation.get(
            "tests_passed"
        ):
            self._invalidate_snapshot(record, "mentor override snapshot is stale or unvalidated")
            raise TaskLifecycleError(
                "mentor learning override requires the current validated snapshot"
            )
        for evidence in record.implementation.get("test_evidence", []):
            path = Path(evidence["path"])
            if (
                not path.is_file()
                or hashlib.sha256(path.read_bytes()).hexdigest() != evidence["sha256"]
            ):
                self._invalidate_snapshot(record, "mentor override validation evidence changed")
                raise TaskLifecycleError(
                    "mentor learning override validation evidence is unavailable"
                )
        evidence = {
            "verified_login": verified_login,
            "repository": "FRC1884/Season2027",
            "membership_state": membership_state,
            "branch": snapshot["branch"],
            "snapshot_digest": snapshot["digest"],
            "plan_digest": record.plan_digest(),
            "interaction_reference": interaction_reference,
            "timestamp": _now(),
            "reason": reason,
        }
        record.learning_review.update(
            {
                "completed": True,
                "status": "MENTOR OVERRIDE",
                "mentor_identity_verified": True,
                "mentor_learning_override_used": True,
                "mentor_override": evidence,
                "interaction_reference": interaction_reference,
                "snapshot_digest": snapshot["digest"],
                "plan_digest": record.plan_digest(),
                "changed_files": snapshot["changed_files"],
            }
        )
        self._event(
            record,
            EventType.MENTOR_IDENTITY_VERIFIED,
            result="verified by native caller",
            metadata=evidence,
        )
        self._event(
            record,
            EventType.MENTOR_LEARNING_OVERRIDE_USED,
            result="MENTOR OVERRIDE",
            metadata=evidence,
        )
        self._transition(
            record,
            TaskState.AWAITING_MENTOR_REVIEW,
            "verified Season2027 mentor waived only learning questions",
        )
        return self._persist(record)

    def guard_action(
        self,
        task_id: str,
        action: str,
        *,
        repository: Path | str,
        path: str | None = None,
    ) -> dict[str, Any]:
        """Gate instrumented author actions. No merge or review-mode authority.

        Call immediately before the action and recheck its result. This helper
        does not execute actions, intercept direct shell access, or authenticate
        caller-supplied interaction provenance.
        """
        record = self.store.load(task_id)
        try:
            if action not in {"implement", "stage", "commit", "push", "author_pr"}:
                raise TaskLifecycleError("action is outside the author workflow")
            if not self._confirmation_valid(record):
                raise TaskLifecycleError("current approved plan required")
            interaction = record.inspection.get("plan_interaction", {})
            if interaction.get("plan_digest") != record.plan_digest():
                raise TaskLifecycleError("explicit current-plan human interaction required")
            if action == "implement":
                self._require_task_worktree(record, repository)
                if record.state is not TaskState.IMPLEMENTING:
                    raise TaskLifecycleError("implementation is not currently unlocked")
                if not record.implementation_permitted:
                    raise TaskLifecycleError("implementation is locked")
                if path is not None and not self.check_edit(task_id, path)["allowed"]:
                    raise TaskLifecycleError("path is outside approved implementation scope")
                return {"allowed": True, "action": action, "plan_digest": record.plan_digest()}
            snapshot = self.snapshot(task_id, repository=repository)
            if action == "stage":
                if record.state not in {
                    TaskState.IMPLEMENTING,
                    TaskState.AWAITING_LEARNING_REVIEW,
                    TaskState.AWAITING_MENTOR_REVIEW,
                }:
                    raise TaskLifecycleError("staging requires active approved implementation")
                if path is None or _normalized_path(path) not in set(
                    record.plan.files_to_modify if record.plan else ()
                ):
                    raise TaskLifecycleError("staging requires an exact covered product path")
                expected = record.implementation.get("snapshot", {})
                if expected and snapshot["digest"] != expected.get("digest"):
                    self._invalidate_snapshot(record, "content changed before staging")
                    raise TaskLifecycleError(
                        "content changed after validation; revalidate before staging"
                    )
                return {"allowed": True, "action": action, **snapshot}
            expected = record.implementation.get("snapshot", {})
            stable_keys = (
                "digest",
                "plan_digest",
                "revision",
                "branch",
                "repository",
                "task_id",
                "session_id",
            )
            if not expected or any(snapshot.get(key) != expected.get(key) for key in stable_keys):
                self._invalidate_snapshot(
                    record, "change snapshot no longer matches validated learning"
                )
                raise TaskLifecycleError("validated learning snapshot is missing or stale")
            if (
                not record.learning_review.get("completed")
                or not record.implementation.get("tests_passed")
                or record.learning_review.get("snapshot_digest") != snapshot["digest"]
                or not record.learning_review.get("interaction_reference")
            ):
                raise TaskLifecycleError(
                    "evaluated human learning is required before author publication"
                )
            for evidence in record.implementation.get("test_evidence", []):
                evidence_path = Path(evidence["path"])
                if (
                    not evidence_path.is_file()
                    or hashlib.sha256(evidence_path.read_bytes()).hexdigest() != evidence["sha256"]
                ):
                    self._invalidate_snapshot(record, "validation evidence changed")
                    raise TaskLifecycleError("validation evidence is missing or changed")
            repo = Path(repository).resolve()
            staged = collect_snapshot(repo, snapshot["base_reference"], staged=True)[0]
            if staged["digest"] != snapshot["digest"]:
                raise TaskLifecycleError("staged change must match the complete approved snapshot")
            if action in {"push", "author_pr"}:
                if run_git(repo, "status", "--porcelain=v1", "--untracked-files=all"):
                    raise TaskLifecycleError("publication requires the committed approved snapshot")
                if snapshot["head_reference"] == expected["head_reference"]:
                    raise TaskLifecycleError("approved snapshot has not been committed")
            self._event(
                record,
                EventType.SCOPE_CHECK_COMPLETED,
                result="author action permitted",
                metadata={
                    "action": action,
                    "snapshot_digest": snapshot["digest"],
                    "plan_digest": record.plan_digest(),
                },
            )
            return {"allowed": True, "action": action, **snapshot}
        except (TaskLifecycleError, ValueError) as error:
            self._event(
                record,
                EventType.POLICY_VIOLATION,
                result=str(error),
                policy_status="violation",
                metadata={"action": action, "plan_digest": record.plan_digest()},
            )
            raise TaskLifecycleError(str(error)) from error

    def mentor_decision(self, task_id: str, reviewer_identifier: str, decision: str) -> TaskRecord:
        record = self.store.load(task_id)
        if record.state is not TaskState.AWAITING_MENTOR_REVIEW:
            raise TaskLifecycleError(
                "mentor decision requires completed tests and post-implementation learning review"
            )
        if not reviewer_identifier.strip():
            raise TaskLifecycleError("mentor reviewer identifier is required")
        if reviewer_identifier.strip() == record.student_identifier:
            raise TaskLifecycleError("the recorded student cannot approve their own task")
        if record.required_reviewer_group not in self.reviewer_authorizations.get(
            reviewer_identifier.strip(), frozenset()
        ):
            raise TaskLifecycleError(
                "reviewer is not authorized for the task's required reviewer group"
            )
        if decision not in {"approved", "rejected"}:
            raise TaskLifecycleError("mentor decision must be approved or rejected")
        target = TaskState.APPROVED if decision == "approved" else TaskState.REJECTED
        self._event(
            record,
            EventType.APPROVAL_RECORDED,
            result=decision,
            reviewer_identifier=reviewer_identifier.strip(),
            metadata={
                "reviewer_identifier": reviewer_identifier,
                "reviewer_group": record.required_reviewer_group,
            },
        )
        self._transition(record, target, f"mentor decision: {decision}")
        return self._persist(record)
