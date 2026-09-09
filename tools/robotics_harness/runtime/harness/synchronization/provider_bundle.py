"""Deterministic local candidate adapters. Integrity is not source authorization.

Adoption still requires the existing synchronization source-attestation and
writeback gates. Editable local manifests cannot establish trusted provenance.
"""

from __future__ import annotations

import ast
import hashlib
import json
import re
import subprocess
from pathlib import Path
from typing import Any

MANIFEST = "tools/robotics_harness/manifest.json"
EXCLUDED = {".git", ".venv", "__pycache__", "node_modules", ".omx"}
INSTRUCTIONS = {"AGENTS.md", "AGENTS.override.md", "CLAUDE.md"}


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def relative_file(root: Path, name: str) -> Path:
    path = root / name
    if path.is_symlink() or not path.resolve().is_relative_to(root.resolve()):
        raise ValueError(f"Unsafe managed path: {name}")
    return path.resolve()


def instruction_scopes(root: Path) -> list[str]:
    return sorted(
        str(path.relative_to(root))
        for path in root.rglob("*")
        if path.name in INSTRUCTIONS
        and path.is_file()
        and not set(path.relative_to(root).parts) & EXCLUDED
        and "runtime" not in path.relative_to(root).parts
    )


def dependency_closure(source: Path, seeds: list[str]) -> list[Path]:
    """Resolve local Python imports without importing or executing source modules."""
    pending = list(seeds)
    seen: set[str] = set()
    files: set[Path] = set()
    while pending:
        module = pending.pop()
        if module in seen:
            continue
        seen.add(module)
        candidate = source.joinpath(*module.split("."))
        path = candidate.with_suffix(".py")
        if not path.is_file():
            path = candidate / "__init__.py"
        if not path.is_file():
            continue
        files.add(path.relative_to(source))
        for parent in path.relative_to(source).parents:
            init = parent / "__init__.py"
            if (source / init).is_file():
                files.add(init)
                pending.append(".".join(parent.parts))
        tree = ast.parse(path.read_text())
        package = module if path.name == "__init__.py" else module.rpartition(".")[0]
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                pending.extend(
                    alias.name for alias in node.names if alias.name.startswith("harness")
                )
            elif isinstance(node, ast.ImportFrom):
                imported = node.module or ""
                if node.level:
                    prefix = package.split(".")[: len(package.split(".")) - node.level + 1]
                    imported = ".".join([*prefix, imported]).rstrip(".")
                if imported == "harness" or imported.startswith("harness."):
                    pending.append(imported)
                    pending.extend(f"{imported}.{alias.name}" for alias in node.names)
    return sorted(files)


def generate_bundle(source: Path, target: Path, profile_name: str) -> dict[str, Any]:
    """Write only manifest-managed outputs, preserving target governance documents."""
    source, target = source.resolve(), target.resolve()
    profile = json.loads((source / "config/agent_targets.json").read_text())["profiles"][
        profile_name
    ]
    template = source / "templates/adapters"
    skill = (
        (template / "SKILL.md")
        .read_text()
        .format(
            required_reading=", ".join(f"`{item}`" for item in profile["required_reading"]),
            review_policy=profile["review_policy"],
            review_template=profile["review_template"],
        )
    )
    outputs: dict[str, bytes] = {
        "CLAUDE.md": (template / "CLAUDE.md.in").read_bytes(),
        ".agents/skills/agentic-review/SKILL.md": skill.encode(),
        ".claude/skills/agentic-review/SKILL.md": skill.encode(),
        ".codex/skills/agentic-review/SKILL.md": (template / "compat-SKILL.md").read_bytes(),
        ".codex/skills/agentic-review/agents/openai.yaml": (
            template / "compat-openai.yaml"
        ).read_bytes(),
        "tools/robotics_harness/entry.py": (template / "entry.py").read_bytes(),
        "tools/robotics_harness/tests/test_smoke.py": (template / "test_smoke.py").read_bytes(),
    }
    for provider, destination in (
        ("codex", ".codex/hooks.json"),
        ("claude", ".claude/settings.json"),
    ):
        hook_template = template / f"{provider}-hooks.json"
        if hook_template.is_file():
            outputs[destination] = hook_template.read_bytes()
    source_hashes = {
        str(path.relative_to(source)): digest(path.read_bytes())
        for path in sorted(template.rglob("*"))
        if path.is_file()
    }
    source_hashes["config/agent_targets.json"] = digest(
        (source / "config/agent_targets.json").read_bytes()
    )
    if profile["runtime_bundle"]:
        modules = dependency_closure(
            source,
            [
                "harness.synchronization.provider_bundle",
                "harness.policy.session_hooks",
                "harness.policy.session_cli",
                "harness.ai_review.session",
            ],
        )
        rules = source / "config/risk_rules.yaml"
        outputs["tools/robotics_harness/runtime/config/risk_rules.yaml"] = rules.read_bytes()
        source_hashes["config/risk_rules.yaml"] = digest(rules.read_bytes())
        author_profiles = source / "config/agent_targets.json"
        outputs["tools/robotics_harness/runtime/config/agent_targets.json"] = (
            author_profiles.read_bytes()
        )
        source_hashes["config/agent_targets.json"] = digest(author_profiles.read_bytes())
        for module_path in modules:
            data = (source / module_path).read_bytes()
            outputs[f"tools/robotics_harness/runtime/{module_path}"] = data
            source_hashes[str(module_path)] = digest(data)
    prior = target / MANIFEST
    if prior.is_file():
        previous = json.loads(prior.read_text())
        for name in previous["outputs"]:
            if name not in outputs:
                obsolete = relative_file(target, name)
                if obsolete.exists():
                    if digest(obsolete.read_bytes()) != previous["outputs"][name]:
                        raise ValueError(f"Preserve modified obsolete generated file: {name}")
                    obsolete.unlink()
    for name, data in outputs.items():
        output_path = relative_file(target, name)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_bytes(data)
    revision = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=source,
        text=True,
        capture_output=True,
        check=True,
    ).stdout.strip()
    references = sorted(
        set([*profile["required_reading"], profile["review_policy"], profile["review_template"]])
    )
    manifest: dict[str, Any] = {
        "schema_version": 1,
        "profile": profile_name,
        "repository": profile["repository"],
        "source_repository": "FRC1884/robotics-agentic-development-harness",
        "source_base_revision": revision,
        "status": "LOCAL_CANDIDATE_NOT_ADOPTED",
        "source_approval": None,
        "source_hashes": source_hashes,
        "outputs": {name: digest(data) for name, data in sorted(outputs.items())},
        "required_references": references,
        "instruction_scopes": instruction_scopes(target),
        "instruction_hashes": {
            name: digest((target / name).read_bytes()) for name in instruction_scopes(target)
        },
    }
    (target / MANIFEST).write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    return manifest


def check_bundle(root: Path) -> list[str]:
    """Return deterministic integrity errors; never issue an authorization verdict."""
    root = root.resolve()
    errors: list[str] = []
    try:
        manifest = json.loads((root / MANIFEST).read_text())
        if (
            manifest["status"] != "LOCAL_CANDIDATE_NOT_ADOPTED"
            or manifest["source_approval"] is not None
        ):
            errors.append("Candidate manifest cannot attest source approval")
        for name, expected in manifest["outputs"].items():
            path = relative_file(root, name)
            if not path.is_file():
                errors.append(f"missing generated file: {name}")
            elif digest(path.read_bytes()) != expected:
                errors.append(f"generated drift: {name}")
        for name in manifest["required_references"]:
            if not relative_file(root, name).is_file():
                errors.append(f"missing required policy reference: {name}")
        if instruction_scopes(root) != manifest["instruction_scopes"]:
            errors.append("instruction scope changed: review nested instructions and regenerate")
        for name, expected in manifest["instruction_hashes"].items():
            path = relative_file(root, name)
            if path.is_file() and digest(path.read_bytes()) != expected:
                errors.append(f"instruction content changed: {name}")
        if manifest["profile"] == "shared":
            for name, expected in manifest["source_hashes"].items():
                path = relative_file(root, name)
                if not path.is_file() or digest(path.read_bytes()) != expected:
                    errors.append(f"adapter source drift: {name}")
        for providers in ((".agents", ".codex"), (".claude",)):
            names: set[str] = set()
            skills = [
                path
                for path in root.rglob("SKILL.md")
                if path.parent.parent.name == "skills"
                and path.parent.parent.parent.name in providers
                and not set(path.relative_to(root).parts) & EXCLUDED
            ]
            for skill in skills:
                match = re.search(r"^name:\s*(.+)$", skill.read_text(), re.MULTILINE)
                if match:
                    name = match[1].strip()
                    if name in names:
                        errors.append(f"duplicate skill in {providers}: {name}")
                    names.add(name)
        graph: dict[str, list[str]] = {}
        for name in instruction_scopes(root):
            path = relative_file(root, name)
            content = path.read_text()
            if len(content.encode()) > 32768:
                errors.append(f"instruction size exceeds 32 KiB: {name}")
            imports = re.findall(r"^@([^\s]+)\s*$", content, re.MULTILINE)
            graph[name] = []
            for imported in imports:
                resolved = relative_file(root, str(path.parent.relative_to(root) / imported))
                if not resolved.is_file():
                    errors.append(f"missing instruction import: {name} -> {imported}")
                else:
                    graph[name].append(str(resolved.relative_to(root)))

        def visit(name: str, stack: set[str]) -> None:
            if name in stack:
                errors.append(f"instruction import cycle: {name}")
                return
            for child in graph.get(name, []):
                visit(child, stack | {name})

        for name in graph:
            visit(name, set())
    except (OSError, ValueError, KeyError, TypeError) as exc:
        errors.append(f"invalid bundle: {exc}")
    return errors


def main() -> int:
    """Generate a local candidate only; this command cannot publish or adopt it."""
    import argparse

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("generate", "check"))
    parser.add_argument("--source", type=Path, default=Path.cwd())
    parser.add_argument("--target", type=Path, required=True)
    parser.add_argument("--profile", choices=("shared", "season2027"))
    args = parser.parse_args()
    if args.action == "generate":
        if not args.profile:
            parser.error("generate requires --profile")
        generate_bundle(args.source.resolve(), args.target.resolve(), args.profile)
    errors = check_bundle(args.target.resolve())
    for error in errors:
        print(error)
    return int(bool(errors))


if __name__ == "__main__":
    raise SystemExit(main())
