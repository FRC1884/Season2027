"""Create evidence-bearing, deterministic student diff reviews."""

from __future__ import annotations

import json
import re
import uuid
from dataclasses import asdict, dataclass
from datetime import UTC, datetime
from pathlib import Path

from harness.git import ChangedFile
from harness.monitoring.models import EventType, MonitoringEvent, format_timestamp
from harness.monitoring.store import EventStore
from harness.private_io import write_private_text
from harness.risk import RiskAssessment, RiskClassifier

_PYTHON_FUNCTION_PATTERN = re.compile(
    r"^[+ ]\s*(?:async\s+)?def\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(([^)]*)\)",
    re.MULTILINE,
)
_JAVA_METHOD_PATTERN = re.compile(
    r"^[+ ]\s*"
    r"(?:(?:public|protected|private|static|final|abstract|synchronized|native|default)\s+)*"
    r"(?:<[^>{}]+>\s+)?"
    r"[A-Za-z_$][A-Za-z0-9_$<>,.? \[\]]*\s+"
    r"([A-Za-z_$][A-Za-z0-9_$]*)\s*\(([^;{}]*)\)\s*(?:throws\s+[^{]+)?\{?",
    re.MULTILINE,
)
_JAVA_CONTROL_WORDS = {"if", "for", "while", "switch", "catch", "return", "new"}
_GENERIC_ANSWERS = {
    "i understand",
    "i understand the code",
    "looks good",
    "yes",
    "no",
    "ok",
    "okay",
    "done",
    "n/a",
}


class AnswerValidationError(ValueError):
    """Raised when a review answer is generic or evidence is incomplete."""


@dataclass(frozen=True, slots=True)
class DiffSection:
    identifier: str
    file: str
    part: int
    line_count: int
    content: str


@dataclass(frozen=True, slots=True)
class ReviewQuestion:
    identifier: str
    prompt: str
    file: str
    section_identifier: str


@dataclass(frozen=True, slots=True)
class QuestionAnswer:
    question_id: str
    answer: str


@dataclass(frozen=True, slots=True)
class VerificationAction:
    action: str
    result: str
    evidence_references: tuple[str, ...]


@dataclass(frozen=True, slots=True)
class ReviewRequest:
    task_identifier: str
    student_identifier: str
    repository: str
    branch: str
    commit: str
    changes: tuple[ChangedFile, ...]
    answers: tuple[str, ...]
    verification_action: str
    verification_result: str
    evidence_references: tuple[str, ...]


@dataclass(frozen=True, slots=True)
class ReviewRecord:
    review_id: str
    task_identifier: str
    student_identifier: str
    repository: str
    branch: str
    commit: str
    changed_files: tuple[str, ...]
    risk_classification: str
    risk_matches: tuple[dict[str, str], ...]
    sections: tuple[DiffSection, ...]
    questions: tuple[ReviewQuestion, ...]
    answers: tuple[QuestionAnswer, ...]
    verification_action: VerificationAction
    completion_state: str
    timestamp: datetime

    def to_dict(self) -> dict[str, object]:
        value = asdict(self)
        value["timestamp"] = format_timestamp(self.timestamp)
        return value


def split_diff(changes: tuple[ChangedFile, ...], max_lines: int = 80) -> tuple[DiffSection, ...]:
    """Split each file diff into bounded, sequential review sections."""

    if max_lines < 10:
        raise ValueError("max_lines must be at least 10")
    sections: list[DiffSection] = []
    for change in changes:
        lines = change.patch.splitlines()
        if not lines:
            lines = [f"diff for {change.path} has no textual content"]
        for offset in range(0, len(lines), max_lines):
            part = offset // max_lines + 1
            content = "\n".join(lines[offset : offset + max_lines])
            sections.append(
                DiffSection(
                    identifier=f"section-{len(sections) + 1}",
                    file=change.path,
                    part=part,
                    line_count=len(content.splitlines()),
                    content=content,
                )
            )
    return tuple(sections)


def _questions_for_section(section: DiffSection) -> list[str]:
    functions = [
        *_PYTHON_FUNCTION_PATTERN.findall(section.content),
        *[
            (name, inputs)
            for name, inputs in _JAVA_METHOD_PATTERN.findall(section.content)
            if name not in _JAVA_CONTROL_WORDS
        ],
    ]
    functions = list(dict.fromkeys(functions))
    questions: list[str] = []
    if functions:
        names = ", ".join(name for name, _inputs in functions[:3])
        questions.append(f"Which changed function ({names}) is relevant, and what does it do?")
        first_name, inputs = functions[0]
        questions.append(
            f"What inputs does {first_name} receive"
            + (f" ({inputs.strip()})" if inputs.strip() else "")
            + ", and which outputs or robot behaviour can change?"
        )
    else:
        questions.append(
            f"What behaviour or repository rule can change because of the edits in {section.file}?"
        )
    lowered = section.content.lower()
    if "min(" in lowered or "clamp" in lowered or "limit" in lowered:
        questions.append("Which condition or calculation limits the requested value?")
    if "max_drive_speed" in lowered or "/safety/" in section.file.lower():
        questions.append(
            "Which file contains the protected maximum, and who must approve changes to it?"
        )
        questions.append("What could happen if the protected speed check were removed?")
    if "test" in section.file.lower() or "assert" in lowered:
        questions.append("Which test validates this behaviour, and what failure would it catch?")
    else:
        questions.append("What test or verification action demonstrates the changed behaviour?")
    return questions[:4]


def generate_questions(sections: tuple[DiffSection, ...]) -> tuple[ReviewQuestion, ...]:
    questions: list[ReviewQuestion] = []
    for section in sections:
        for prompt in _questions_for_section(section):
            questions.append(
                ReviewQuestion(
                    identifier=f"question-{len(questions) + 1}",
                    prompt=prompt,
                    file=section.file,
                    section_identifier=section.identifier,
                )
            )
    return tuple(questions)


def validate_answer(answer: str) -> None:
    normalized = " ".join(answer.lower().strip().rstrip(".!").split())
    if normalized in _GENERIC_ANSWERS or normalized.startswith("i understand"):
        raise AnswerValidationError(
            "generic confirmation is not evidence; describe the code or behaviour"
        )
    words = re.findall(r"[A-Za-z0-9_]+", normalized)
    if len(words) < 5 or len(set(words)) < 4:
        raise AnswerValidationError(
            "answer must contain at least five substantive words about the code"
        )


class ReviewEngine:
    """Generate, validate, persist, and report a completed diff walkthrough."""

    def __init__(
        self,
        *,
        classifier: RiskClassifier | None = None,
        output_directory: Path | str = "artifacts/reviews",
        event_store: EventStore | None = None,
    ) -> None:
        self.classifier = classifier or RiskClassifier()
        self.output_directory = Path(output_directory)
        self.event_store = event_store

    def prepare(
        self, changes: tuple[ChangedFile, ...]
    ) -> tuple[tuple[DiffSection, ...], tuple[ReviewQuestion, ...], RiskAssessment]:
        if not changes:
            raise ValueError("the diff contains no changed files")
        sections = split_diff(changes)
        return sections, generate_questions(sections), self.classifier.classify(changes)

    def complete(self, request: ReviewRequest) -> tuple[ReviewRecord, Path, Path]:
        sections, questions, assessment = self.prepare(request.changes)
        if len(request.answers) != len(questions):
            raise AnswerValidationError(
                f"expected {len(questions)} answers, received {len(request.answers)}"
            )
        for answer in request.answers:
            validate_answer(answer)
        if not request.verification_action.strip():
            raise AnswerValidationError("at least one verification action is required")
        if not request.verification_result.strip():
            raise AnswerValidationError("the verification result is required")
        if not request.evidence_references:
            raise AnswerValidationError("verification requires at least one evidence reference")

        record = ReviewRecord(
            review_id=str(uuid.uuid4()),
            task_identifier=request.task_identifier,
            student_identifier=request.student_identifier,
            repository=request.repository,
            branch=request.branch,
            commit=request.commit,
            changed_files=tuple(change.path for change in request.changes),
            risk_classification=assessment.risk.label,
            risk_matches=tuple(match.to_dict() for match in assessment.matches),
            sections=sections,
            questions=questions,
            answers=tuple(
                QuestionAnswer(question.identifier, answer)
                for question, answer in zip(questions, request.answers, strict=True)
            ),
            verification_action=VerificationAction(
                action=request.verification_action,
                result=request.verification_result,
                evidence_references=request.evidence_references,
            ),
            completion_state="completed",
            timestamp=datetime.now(UTC),
        )
        record_path = self.output_directory / f"{record.review_id}.json"
        report_path = self.output_directory / f"{record.review_id}.md"
        write_private_text(
            record_path,
            json.dumps(record.to_dict(), indent=2, sort_keys=True) + "\n",
        )
        write_private_text(report_path, self.mentor_report(record))
        self._record_events(record, record_path)
        return record, record_path, report_path

    def _record_events(self, record: ReviewRecord, record_path: Path) -> None:
        if self.event_store is None:
            return
        self.event_store.append(
            MonitoringEvent.create(
                event_type=EventType.LEARNING_LOOP_STARTED,
                session_id=f"learning-{record.review_id}",
                student_identifier=record.student_identifier,
                repository=record.repository,
                branch=record.branch,
                task_identifier=record.task_identifier,
                commit_or_pr_reference=record.commit,
                result="started",
            )
        )
        self.event_store.append(
            MonitoringEvent.create(
                event_type=EventType.LEARNING_LOOP_COMPLETED,
                session_id=f"learning-{record.review_id}",
                student_identifier=record.student_identifier,
                repository=record.repository,
                branch=record.branch,
                task_identifier=record.task_identifier,
                commit_or_pr_reference=record.commit,
                result="completed",
                metadata={"review_record": str(record_path)},
            )
        )

    @staticmethod
    def mentor_report(record: ReviewRecord) -> str:
        lines = [
            f"# Learning-loop review {record.review_id}",
            "",
            f"- Task: `{record.task_identifier}`",
            f"- Student identifier: `{record.student_identifier}`",
            f"- Repository: `{record.repository}`",
            f"- Branch / commit: `{record.branch}` / `{record.commit}`",
            f"- Risk: **{record.risk_classification}**",
            f"- Completion: **{record.completion_state}**",
            "",
            "## Changed files",
            "",
        ]
        lines.extend(f"- `{path}`" for path in record.changed_files)
        lines.extend(["", "## Walkthrough evidence", ""])
        answer_by_id = {answer.question_id: answer.answer for answer in record.answers}
        for question in record.questions:
            lines.extend(
                [
                    f"### {question.identifier}: {question.file}",
                    "",
                    question.prompt,
                    "",
                    f"> {answer_by_id[question.identifier]}",
                    "",
                ]
            )
        lines.extend(
            [
                "## Verification",
                "",
                f"- Action: `{record.verification_action.action}`",
                f"- Result: {record.verification_action.result}",
                "- Evidence:",
            ]
        )
        lines.extend(
            f"  - `{reference}`" for reference in record.verification_action.evidence_references
        )
        return "\n".join(lines) + "\n"
