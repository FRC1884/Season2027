"""Protected-path and evidence validation for a proposed change set."""

from __future__ import annotations

import fnmatch
from dataclasses import dataclass, field

from harness.git import ChangedFile


@dataclass(frozen=True, slots=True)
class ProtectedPathRule:
    identifier: str
    patterns: tuple[str, ...]
    required_approval: str
    explanation: str


DEFAULT_PROTECTED_PATHS = (
    ProtectedPathRule(
        identifier="agent-policy",
        patterns=(
            "AGENTS.md",
            "**/AGENTS.md",
            "AGENTS.override.md",
            "**/AGENTS.override.md",
            "CLAUDE.md",
            "**/CLAUDE.md",
            ".claude/**",
            "**/.claude/**",
            ".agents/**",
            "**/.agents/**",
            ".codex/**",
            "**/.codex/**",
            "templates/AGENTS.md",
            "templates/adapters/**",
            "templates/robotics-harness/**",
        ),
        required_approval="Harness Administrators",
        explanation="agent policy changes require explicit governance review",
    ),
    ProtectedPathRule(
        identifier="workflow-policy",
        patterns=(
            ".github/workflows/**",
            "**/.github/workflows/**",
            ".github/CODEOWNERS",
            "**/.github/CODEOWNERS",
        ),
        required_approval="Harness Administrators",
        explanation="CI workflows and Code Owners enforce mandatory checks",
    ),
    ProtectedPathRule(
        identifier="risk-policy",
        patterns=(
            "config/risk_rules.yaml",
            "**/config/risk_rules.yaml",
            "harness/policy/**",
            "harness/risk/**",
        ),
        required_approval="Harness Administrators",
        explanation="risk rules must not be downgraded by task authors",
    ),
    ProtectedPathRule(
        identifier="governance-policy",
        patterns=(
            "config/github_governance.yaml",
            "config/managed_files.yaml",
            "config/review_policy.yaml",
            "config/agent_targets.json",
            ".github/robotics-harness/**",
            "tools/robotics_harness/**",
            "harness/tasks/**",
            "harness/learning_loop/**",
            "config/temporary_harness_access.schema.json",
            ".github/codex/**",
            "harness/ai_review/**",
            "harness/competition/**",
            "harness/github_governance/**",
            "harness/monitoring/**",
            "harness/review_policy/**",
            "harness/synchronization/**",
            "scripts/codex-review",
            "scripts/github-governance",
            "scripts/harness-sync",
        ),
        required_approval="Harness Administrators",
        explanation=(
            "canonical governance, Codex review, and managed-file policy require "
            "Harness governance review"
        ),
    ),
    ProtectedPathRule(
        identifier="robot-safety",
        patterns=("safety/**", "**/safety/**"),
        required_approval="Safety Code Owners",
        explanation="robot safety code and protected limits need safety review",
    ),
    ProtectedPathRule(
        identifier="deployment",
        patterns=(
            "deploy/**",
            "**/deploy/**",
            "deployment/**",
            "**/deployment/**",
            "config/deployment*",
            "**/config/deployment*",
        ),
        required_approval="Safety Code Owners",
        explanation="deployment changes can directly affect the robot",
    ),
)


@dataclass(frozen=True, slots=True)
class PolicyContext:
    """Evidence and authorization available when validating a change."""

    branch: str = ""
    default_branch: str = "main"
    via_pull_request: bool = True
    approval_groups: frozenset[str] = field(default_factory=frozenset)
    task_scope_patterns: tuple[str, ...] = ()
    scope_expansion_recorded: bool = False
    claimed_tests_passed: bool = False
    test_execution_verified: bool = False
    test_commands: tuple[str, ...] = ()
    test_evidence_references: tuple[str, ...] = ()


@dataclass(frozen=True, slots=True)
class PolicyViolation:
    code: str
    message: str
    mandatory: bool
    files: tuple[str, ...] = ()
    required_approval: str | None = None

    def to_dict(self) -> dict[str, object]:
        return {
            "code": self.code,
            "message": self.message,
            "mandatory": self.mandatory,
            "files": list(self.files),
            "required_approval": self.required_approval,
        }


@dataclass(frozen=True, slots=True)
class PolicyReport:
    violations: tuple[PolicyViolation, ...]
    protected_paths_touched: tuple[str, ...]

    @property
    def passed(self) -> bool:
        return not any(violation.mandatory for violation in self.violations)

    def to_dict(self) -> dict[str, object]:
        return {
            "passed": self.passed,
            "protected_paths_touched": list(self.protected_paths_touched),
            "violations": [violation.to_dict() for violation in self.violations],
        }


def _matches(path: str, patterns: tuple[str, ...]) -> bool:
    normalized = path.removeprefix("./")
    return any(fnmatch.fnmatchcase(normalized, pattern) for pattern in patterns)


class PolicyValidator:
    """Validate mandatory repository controls against recorded evidence."""

    def __init__(
        self, protected_path_rules: tuple[ProtectedPathRule, ...] = DEFAULT_PROTECTED_PATHS
    ) -> None:
        self.protected_path_rules = protected_path_rules

    def validate(
        self,
        changes: tuple[ChangedFile, ...] | list[ChangedFile],
        context: PolicyContext,
    ) -> PolicyReport:
        violations: list[PolicyViolation] = []
        protected: set[str] = set()

        if context.branch == context.default_branch and not context.via_pull_request:
            violations.append(
                PolicyViolation(
                    code="direct-default-branch-change",
                    message="changes to the protected default branch must use a pull request",
                    mandatory=True,
                )
            )

        for rule in self.protected_path_rules:
            touched = sorted(
                {
                    path
                    for change in changes
                    for path in change.affected_paths
                    if _matches(path, rule.patterns)
                }
            )
            if not touched:
                continue
            protected.update(touched)
            if rule.required_approval not in context.approval_groups:
                violations.append(
                    PolicyViolation(
                        code=f"protected-path-{rule.identifier}",
                        message=rule.explanation,
                        mandatory=True,
                        files=tuple(touched),
                        required_approval=rule.required_approval,
                    )
                )

        if context.task_scope_patterns and not context.scope_expansion_recorded:
            out_of_scope = sorted(
                {
                    path
                    for change in changes
                    for path in change.affected_paths
                    if not _matches(path, context.task_scope_patterns)
                }
            )
            if out_of_scope:
                violations.append(
                    PolicyViolation(
                        code="unrecorded-scope-expansion",
                        message=(
                            "files outside the recorded task scope require a scope-expansion record"
                        ),
                        mandatory=True,
                        files=tuple(out_of_scope),
                    )
                )

        if context.claimed_tests_passed and (
            not context.test_commands
            or not context.test_evidence_references
            or not context.test_execution_verified
        ):
            violations.append(
                PolicyViolation(
                    code="unsubstantiated-test-claim",
                    message=(
                        "a test-passed claim requires command, evidence, and a matching "
                        "successful harness test event"
                    ),
                    mandatory=True,
                )
            )

        return PolicyReport(
            violations=tuple(violations),
            protected_paths_touched=tuple(sorted(protected)),
        )

    def authorize_task_edit(
        self,
        path: str,
        *,
        implementation_unlocked: bool,
        planned_paths: tuple[str, ...],
        approval_groups: frozenset[str] = frozenset(),
    ) -> PolicyReport:
        """Hard gate one proposed edit against confirmation, scope, and protected paths."""

        violations: list[PolicyViolation] = []
        if not implementation_unlocked:
            violations.append(
                PolicyViolation(
                    code="implementation-locked",
                    message="implementation requires a valid confirmed plan",
                    mandatory=True,
                    files=(path,),
                )
            )
        if not any(_matches(path, (planned,)) for planned in planned_paths):
            violations.append(
                PolicyViolation(
                    code="outside-confirmed-plan",
                    message="the path is not included in the confirmed implementation plan",
                    mandatory=True,
                    files=(path,),
                )
            )
        protected_report = self.validate(
            [ChangedFile(path=path, patch="")],
            PolicyContext(approval_groups=approval_groups),
        )
        violations.extend(protected_report.violations)
        return PolicyReport(
            violations=tuple(violations),
            protected_paths_touched=protected_report.protected_paths_touched,
        )
