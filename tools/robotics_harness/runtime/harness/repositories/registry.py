"""Register, inspect, and isolate external local Git repositories safely."""

from __future__ import annotations

import json
import os
import re
import subprocess
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

from harness.git import GitCommandError, parse_unified_diff, resolve_commit, run_git
from harness.policy import PolicyContext, PolicyReport, PolicyValidator
from harness.policy.validator import DEFAULT_PROTECTED_PATHS, ProtectedPathRule

DEFAULT_WPILIB_PROTECTED_PATHS = (
    "src/main/java/**/constants/**",
    "src/main/java/**/*Constants.java",
    "src/main/java/**/safety/**",
    "src/main/deploy/**",
    ".github/workflows/**",
    "build.gradle",
    "settings.gradle",
    "gradle/**",
    "vendordeps/**",
    "AGENTS.md",
)

_SYMBOL_PATTERN = re.compile(
    r"^\s*(?:public|protected|private)?\s*"
    r"(?:(?:static|final|abstract|synchronized)\s+)*"
    r"(?:class|interface|enum|record|void|boolean|byte|char|short|int|long|float|double|"
    r"[A-Z][A-Za-z0-9_<>, ?.\[\]]*)\s+"
    r"([A-Za-z_][A-Za-z0-9_]*)\s*(?:\(|\{|=)",
    re.MULTILINE,
)


class RepositorySafetyError(RuntimeError):
    """Raised when an external repository operation would violate a guardrail."""


@dataclass(frozen=True, slots=True)
class RegisteredRepository:
    """One user-configured external local repository."""

    name: str
    path: str
    repository_type: str
    default_branch: str
    protected_paths: tuple[str, ...]
    expected_remote: str = ""

    def to_dict(self) -> dict[str, object]:
        value = asdict(self)
        value["protected_paths"] = list(self.protected_paths)
        return value

    @classmethod
    def from_dict(cls, value: dict[str, Any]) -> RegisteredRepository:
        protected = value.get("protected_paths", [])
        if not isinstance(protected, list) or not all(isinstance(item, str) for item in protected):
            raise ValueError("protected_paths must be a list of strings")
        return cls(
            name=str(value["name"]),
            path=str(value["path"]),
            repository_type=str(value.get("repository_type", "unknown")),
            default_branch=str(value.get("default_branch", "main")),
            protected_paths=tuple(protected),
            expected_remote=str(value.get("expected_remote", "")),
        )


@dataclass(frozen=True, slots=True)
class RepositoryInspection:
    """Read-only evidence gathered from a registered repository."""

    name: str
    resolved_path: str
    current_branch: str
    remote_default_branch: str
    head_commit: str
    clean: bool
    dirty_paths: tuple[str, ...]
    remotes: tuple[str, ...]
    instruction_files: tuple[str, ...]
    build_files: tuple[str, ...]
    test_files: tuple[str, ...]
    relevant_files: tuple[str, ...]
    relevant_symbols: tuple[str, ...]
    validation_commands: tuple[str, ...]
    expected_remote: str
    remote_identity_verified: bool

    def to_dict(self) -> dict[str, object]:
        value = asdict(self)
        for key in (
            "dirty_paths",
            "remotes",
            "instruction_files",
            "build_files",
            "test_files",
            "relevant_files",
            "relevant_symbols",
            "validation_commands",
        ):
            value[key] = list(value[key])
        return value


@dataclass(frozen=True, slots=True)
class WorktreeResult:
    """An isolated demo branch and worktree created from a recorded base."""

    source_repository: str
    base_ref: str
    base_commit: str
    branch: str
    worktree_path: str

    def to_dict(self) -> dict[str, str]:
        return asdict(self)


class RepositoryRegistry:
    """Persist external repository registrations outside versioned config."""

    def __init__(self, path: Path | str = "artifacts/repositories.json") -> None:
        self.path = Path(path)

    def _load(self) -> dict[str, RegisteredRepository]:
        if not self.path.exists():
            return {}
        try:
            raw: Any = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            raise RepositorySafetyError(
                f"cannot read repository registry {self.path}: {error}"
            ) from error
        if not isinstance(raw, dict) or raw.get("schema_version") != 1:
            raise RepositorySafetyError("repository registry must use schema_version 1")
        repositories = raw.get("repositories", [])
        if not isinstance(repositories, list):
            raise RepositorySafetyError("repository registry entries must be a list")
        try:
            entries = [RegisteredRepository.from_dict(item) for item in repositories]
        except (KeyError, TypeError, ValueError) as error:
            raise RepositorySafetyError(f"invalid repository registration: {error}") from error
        return {entry.name: entry for entry in entries}

    def _save(self, entries: dict[str, RegisteredRepository]) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        payload = {
            "schema_version": 1,
            "repositories": [entries[name].to_dict() for name in sorted(entries)],
        }
        temporary = self.path.with_suffix(self.path.suffix + ".tmp")
        flags = os.O_WRONLY | os.O_CREAT | os.O_TRUNC
        if hasattr(os, "O_NOFOLLOW"):
            flags |= os.O_NOFOLLOW
        descriptor = os.open(
            temporary,
            flags,
            0o600,
        )
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            stream.write(json.dumps(payload, indent=2, sort_keys=True) + "\n")
            stream.flush()
        os.chmod(temporary, 0o600)
        temporary.replace(self.path)
        os.chmod(self.path, 0o600)

    def register(
        self,
        *,
        name: str,
        path: Path | str,
        repository_type: str,
        default_branch: str = "",
        protected_paths: tuple[str, ...] | None = None,
        expected_remote: str = "",
    ) -> RegisteredRepository:
        normalized_name = name.strip()
        valid_name = re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", normalized_name)
        if not normalized_name or valid_name is None:
            raise RepositorySafetyError(
                "repository name must contain only letters, digits, dot, underscore, or hyphen"
            )
        resolved = Path(path).expanduser().resolve()
        if not resolved.is_dir():
            raise RepositorySafetyError(f"repository path does not exist: {resolved}")
        try:
            resolved = Path(run_git(resolved, "rev-parse", "--show-toplevel")).resolve()
        except GitCommandError as error:
            raise RepositorySafetyError(f"path is not a Git repository: {resolved}") from error

        patterns = (
            DEFAULT_WPILIB_PROTECTED_PATHS
            if protected_paths is None and repository_type == "wpilib-java"
            else protected_paths or ()
        )
        selected_default_branch = (
            default_branch.strip() or ExternalRepositoryInspector.discover_default_branch(resolved)
        )
        registration = RegisteredRepository(
            name=normalized_name,
            path=str(resolved),
            repository_type=repository_type.strip() or "unknown",
            default_branch=selected_default_branch,
            protected_paths=tuple(patterns),
            expected_remote=expected_remote.strip(),
        )
        entries = self._load()
        entries[registration.name] = registration
        self._save(entries)
        return registration

    def get(self, name: str) -> RegisteredRepository:
        try:
            return self._load()[name]
        except KeyError as error:
            raise RepositorySafetyError(f"repository is not registered: {name}") from error

    def list(self) -> tuple[RegisteredRepository, ...]:
        entries = self._load()
        return tuple(entries[name] for name in sorted(entries))


class ExternalRepositoryInspector:
    """Perform read-only discovery and confirmation-gated worktree creation."""

    @staticmethod
    def discover_default_branch(repo: Path | str) -> str:
        """Discover the remote default branch without mutating repository metadata."""
        resolved = Path(repo).expanduser().resolve()
        remote_head = subprocess.run(
            [
                "git",
                "-C",
                str(resolved),
                "symbolic-ref",
                "--quiet",
                "--short",
                "refs/remotes/origin/HEAD",
            ],
            check=False,
            capture_output=True,
            text=True,
        )
        if remote_head.returncode == 0:
            value = remote_head.stdout.strip()
            if value.startswith("origin/") and len(value) > len("origin/"):
                return value.removeprefix("origin/")

        local_head = subprocess.run(
            [
                "git",
                "-C",
                str(resolved),
                "symbolic-ref",
                "--quiet",
                "--short",
                "HEAD",
            ],
            check=False,
            capture_output=True,
            text=True,
        )
        if local_head.returncode == 0 and local_head.stdout.strip():
            return local_head.stdout.strip()
        raise RepositorySafetyError(
            "cannot discover the default branch from origin/HEAD or the current branch"
        )

    def inspect(
        self,
        registration: RegisteredRepository,
        *,
        request: str = "",
        maximum_files: int = 12,
    ) -> RepositoryInspection:
        repo = Path(registration.path).resolve()
        if maximum_files < 1:
            raise ValueError("maximum_files must be positive")
        top_level = Path(run_git(repo, "rev-parse", "--show-toplevel")).resolve()
        if top_level != repo:
            repo = top_level

        status_result = subprocess.run(
            [
                "git",
                "-C",
                str(repo),
                "status",
                "--porcelain=v1",
                "--untracked-files=all",
            ],
            check=False,
            capture_output=True,
            text=True,
        )
        if status_result.returncode != 0:
            detail = status_result.stderr.strip() or "unknown Git status error"
            raise RepositorySafetyError(f"cannot inspect repository status: {detail}")
        status_lines = status_result.stdout.splitlines()
        dirty_paths = tuple(line[3:] if len(line) > 3 else line for line in status_lines if line)
        current_branch = run_git(repo, "branch", "--show-current")
        remote_default_branch = self._remote_default_branch(repo)
        head_commit = run_git(repo, "rev-parse", "HEAD")
        remotes = tuple(line for line in run_git(repo, "remote", "-v").splitlines() if line)
        remote_identity_verified = self._remote_matches(
            remotes,
            registration.expected_remote,
        )

        tracked = tuple(line for line in run_git(repo, "ls-files").splitlines() if line)
        instruction_files = tuple(
            path
            for path in tracked
            if path == "AGENTS.md"
            or path.endswith("/AGENTS.md")
            or path in {"README.md", "ARCHITECTURE.md", "docs/CODEX_COORDINATION.md"}
        )
        build_files = tuple(
            path
            for path in tracked
            if path
            in {
                "build.gradle",
                "build.gradle.kts",
                "settings.gradle",
                "settings.gradle.kts",
                "pom.xml",
                "pyproject.toml",
            }
            or path.startswith(".github/workflows/")
        )
        test_files = tuple(
            path
            for path in tracked
            if path.startswith(("src/test/", "tests/", "test/"))
            and Path(path).suffix in {".java", ".py", ".kt"}
        )
        relevant_files = self._relevant_files(
            repo,
            tracked,
            request=request,
            maximum_files=maximum_files,
        )
        relevant_symbols = self._symbols(repo, relevant_files)
        validation_commands = self._validation_commands(repo, tracked)
        return RepositoryInspection(
            name=registration.name,
            resolved_path=str(repo),
            current_branch=current_branch,
            remote_default_branch=remote_default_branch,
            head_commit=head_commit,
            clean=not status_lines,
            dirty_paths=dirty_paths,
            remotes=remotes,
            instruction_files=instruction_files,
            build_files=build_files,
            test_files=test_files,
            relevant_files=relevant_files,
            relevant_symbols=relevant_symbols,
            validation_commands=validation_commands,
            expected_remote=registration.expected_remote,
            remote_identity_verified=remote_identity_verified,
        )

    @staticmethod
    def _remote_default_branch(repo: Path) -> str:
        completed = subprocess.run(
            [
                "git",
                "-C",
                str(repo),
                "symbolic-ref",
                "--quiet",
                "--short",
                "refs/remotes/origin/HEAD",
            ],
            check=False,
            capture_output=True,
            text=True,
        )
        if completed.returncode != 0:
            return ""
        value = completed.stdout.strip()
        return value.removeprefix("origin/") if value.startswith("origin/") else ""

    @staticmethod
    def _remote_matches(remotes: tuple[str, ...], expected: str) -> bool:
        if not expected:
            return True
        normalized_expected = ExternalRepositoryInspector._normalize_remote(expected)
        origin_urls = [
            parts[1] for line in remotes if len(parts := line.split()) >= 2 and parts[0] == "origin"
        ]
        return bool(origin_urls) and all(
            ExternalRepositoryInspector._normalize_remote(url) == normalized_expected
            for url in origin_urls
        )

    @staticmethod
    def _normalize_remote(remote: str) -> str:
        normalized = remote.strip().removesuffix("/").removesuffix(".git")
        if normalized.startswith("git@github.com:"):
            normalized = "github.com/" + normalized.removeprefix("git@github.com:")
        elif normalized.startswith("ssh://git@github.com/"):
            normalized = "github.com/" + normalized.removeprefix("ssh://git@github.com/")
        elif normalized.startswith("https://github.com/"):
            normalized = "github.com/" + normalized.removeprefix("https://github.com/")
        return normalized.lower()

    @staticmethod
    def _request_tokens(request: str) -> tuple[str, ...]:
        ignored = {
            "a",
            "an",
            "and",
            "can",
            "code",
            "fix",
            "for",
            "in",
            "make",
            "our",
            "small",
            "something",
            "the",
            "to",
            "useful",
        }
        tokens = {
            token
            for token in re.findall(r"[a-z0-9]+", request.lower())
            if len(token) >= 3 and token not in ignored
        }
        return tuple(sorted(tokens))

    def _relevant_files(
        self,
        repo: Path,
        tracked: tuple[str, ...],
        *,
        request: str,
        maximum_files: int,
    ) -> tuple[str, ...]:
        tokens = self._request_tokens(request)
        candidates: list[tuple[int, str]] = []
        for path in tracked:
            suffix = Path(path).suffix.lower()
            if suffix not in {".java", ".kt", ".py", ".js", ".json"}:
                continue
            lowered = path.lower()
            score = sum(token in lowered for token in tokens) * 3
            if "/test/" in lowered or lowered.startswith("tests/"):
                score += 1
            if score == 0 and tokens:
                try:
                    content = (repo / path).read_text(encoding="utf-8", errors="ignore")
                except OSError:
                    continue
                score = sum(token in content.lower() for token in tokens)
            if score:
                candidates.append((score, path))
        candidates.sort(key=lambda item: (-item[0], item[1]))
        if not candidates:
            candidates = [
                (0, path)
                for path in tracked
                if path.startswith(("src/main/", "src/test/", "tests/"))
                and Path(path).suffix.lower() in {".java", ".kt", ".py"}
            ]
        return tuple(path for _score, path in candidates[:maximum_files])

    @staticmethod
    def _symbols(repo: Path, paths: tuple[str, ...]) -> tuple[str, ...]:
        symbols: list[str] = []
        for path in paths:
            if Path(path).suffix not in {".java", ".kt"}:
                continue
            try:
                content = (repo / path).read_text(encoding="utf-8", errors="ignore")
            except OSError:
                continue
            for name in _SYMBOL_PATTERN.findall(content):
                reference = f"{path}:{name}"
                if reference not in symbols:
                    symbols.append(reference)
                if len(symbols) >= 30:
                    return tuple(symbols)
        return tuple(symbols)

    @staticmethod
    def _validation_commands(repo: Path, tracked: tuple[str, ...]) -> tuple[str, ...]:
        commands: list[str] = []
        if "gradlew" in tracked and "build.gradle" in tracked:
            commands.extend(("./gradlew test", "./gradlew build"))
            workflow_paths = (path for path in tracked if path.startswith(".github/workflows/"))
            for path in workflow_paths:
                try:
                    workflow = (repo / path).read_text(encoding="utf-8", errors="ignore")
                except OSError:
                    continue
                for command in re.findall(
                    r"(?m)^\s*run:\s*(\./gradlew\s+[A-Za-z0-9:_-]+)", workflow
                ):
                    if "deploy" not in command.lower() and command not in commands:
                        commands.append(command)
        elif "pyproject.toml" in tracked:
            commands.append("python -m pytest")
        return tuple(commands)

    def create_worktree(
        self,
        registration: RegisteredRepository,
        *,
        task_state: str,
        confirmation_digest: str,
        base_ref: str | None = None,
        branch_prefix: str = "demo/agentic-harness-learning-loop",
        destination_parent: Path | str | None = None,
        expected_base_commit: str = "",
    ) -> WorktreeResult:
        if task_state != "approved_for_implementation" or not confirmation_digest.strip():
            raise RepositorySafetyError(
                "worktree creation requires a confirmed approved_for_implementation task"
            )
        if not registration.expected_remote:
            raise RepositorySafetyError(
                "worktree creation requires a configured expected origin remote"
            )
        inspection = self.inspect(registration)
        if not inspection.remote_identity_verified:
            raise RepositorySafetyError(
                "target repository remotes do not match the configured expected remote"
            )
        if not inspection.clean:
            paths = ", ".join(inspection.dirty_paths[:8])
            raise RepositorySafetyError(
                f"target working tree is dirty; refusing demo worktree creation: {paths}"
            )
        if inspection.current_branch != registration.default_branch:
            raise RepositorySafetyError("target checkout is not on the configured default branch")
        if (
            inspection.remote_default_branch
            and inspection.remote_default_branch != registration.default_branch
        ):
            raise RepositorySafetyError(
                "configured default branch does not match the remote default branch"
            )
        if expected_base_commit and inspection.head_commit != expected_base_commit:
            raise RepositorySafetyError(
                "target base changed after plan confirmation; reconfirmation is required"
            )

        repo = Path(inspection.resolved_path)
        requested_base = base_ref or registration.default_branch
        try:
            base_commit = resolve_commit(repo, requested_base)
        except GitCommandError as error:
            raise RepositorySafetyError(
                f"cannot resolve verified base ref {requested_base!r}"
            ) from error
        if expected_base_commit and base_commit != expected_base_commit:
            raise RepositorySafetyError(
                "requested base does not match the plan-confirmed repository commit"
            )

        branch = self._unique_branch(repo, branch_prefix)
        parent = (
            Path(destination_parent).expanduser().resolve()
            if destination_parent is not None
            else repo.parent
        )
        if parent == repo or parent.is_relative_to(repo):
            raise RepositorySafetyError(
                "external demo worktree must be created outside the target checkout"
            )
        parent.mkdir(parents=True, exist_ok=True)
        worktree = self._unique_worktree_path(parent, f"{repo.name}-harness-demo")
        try:
            run_git(repo, "worktree", "add", str(worktree), "-b", branch, base_commit)
        except GitCommandError as error:
            raise RepositorySafetyError(f"cannot create isolated demo worktree: {error}") from error
        return WorktreeResult(
            source_repository=str(repo),
            base_ref=requested_base,
            base_commit=base_commit,
            branch=branch,
            worktree_path=str(worktree),
        )

    @staticmethod
    def _unique_branch(repo: Path, prefix: str) -> str:
        normalized = prefix.strip().strip("/")
        if not normalized:
            raise RepositorySafetyError("branch prefix is required")
        if normalized.startswith("-"):
            raise RepositorySafetyError("branch prefix is not a valid local Git branch")
        valid_ref = subprocess.run(
            [
                "git",
                "-C",
                str(repo),
                "check-ref-format",
                f"refs/heads/{normalized}",
            ],
            check=False,
            capture_output=True,
            text=True,
        )
        if valid_ref.returncode != 0:
            raise RepositorySafetyError("branch prefix is not a valid local Git branch")
        for index in range(1, 1000):
            candidate = normalized if index == 1 else f"{normalized}-{index}"
            # `show-ref --exists` is not available on every supported Git version.
            result = subprocess.run(
                [
                    "git",
                    "-C",
                    str(repo),
                    "show-ref",
                    "--verify",
                    "--quiet",
                    f"refs/heads/{candidate}",
                ],
                check=False,
                capture_output=True,
                text=True,
            )
            if result.returncode == 1:
                return candidate
            if result.returncode not in {0, 1}:
                raise RepositorySafetyError("cannot inspect existing demo branches")
        raise RepositorySafetyError("could not allocate a unique demo branch")

    @staticmethod
    def _unique_worktree_path(parent: Path, name: str) -> Path:
        for index in range(1, 1000):
            candidate = parent / (name if index == 1 else f"{name}-{index}")
            if not candidate.exists():
                return candidate
        raise RepositorySafetyError("could not allocate a unique worktree directory")


def evaluate_protected_diff(
    diff_text: str,
    protected_paths: tuple[str, ...],
    *,
    approval_groups: frozenset[str] = frozenset(),
) -> PolicyReport:
    """Evaluate a proposed diff without applying it to the target repository."""

    changes = parse_unified_diff(diff_text)
    rule = ProtectedPathRule(
        identifier="external-repository-protected",
        patterns=protected_paths,
        required_approval="Safety Code Owners",
        explanation="external robot repository protected paths require safety review",
    )
    return PolicyValidator((rule,)).validate(
        changes,
        PolicyContext(approval_groups=approval_groups),
    )


def policy_validator_for_registration(
    registration: RegisteredRepository,
) -> PolicyValidator:
    """Build the task policy that is persisted for a registered target."""

    external = ProtectedPathRule(
        identifier="external-repository-protected",
        patterns=registration.protected_paths,
        required_approval="Safety Code Owners",
        explanation="external robot repository protected paths require safety review",
    )
    return PolicyValidator((*DEFAULT_PROTECTED_PATHS, external))
