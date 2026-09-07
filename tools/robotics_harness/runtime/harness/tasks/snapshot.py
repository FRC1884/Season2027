"""Read-only content snapshots, independent of staging and commit timing.

Ignored untracked files are outside the product snapshot. Evidence must live
outside the checkout. A concurrent writer can invalidate a snapshot; callers
recheck immediately before actions. These local records are not signed proof.
"""

from __future__ import annotations

import difflib
import hashlib
import json
import os
import stat
import subprocess
from pathlib import Path
from typing import Any

from harness.git import ChangedFile, resolve_commit, run_git


def _git(repo: Path, *args: str) -> bytes:
    return subprocess.run(["git", "-C", str(repo), *args], check=True, capture_output=True).stdout


def _tree(repo: Path, revision: str) -> dict[str, tuple[str, str]]:
    entries = {}
    for entry in _git(repo, "ls-tree", "-rz", revision).split(b"\0"):
        if not entry:
            continue
        metadata, raw_path = entry.split(b"\t", 1)
        mode, kind, oid = metadata.decode().split()
        if kind != "blob":
            raise ValueError("submodule snapshots require separate isolated validation")
        entries[os.fsdecode(raw_path)] = (mode, oid)
    return entries


def _blob(content: bytes, algorithm: str) -> str:
    return hashlib.new(
        algorithm, b"blob " + str(len(content)).encode() + b"\0" + content
    ).hexdigest()


def collect_snapshot(
    repo: Path, base: str, *, staged: bool = False
) -> tuple[dict[str, Any], tuple[ChangedFile, ...]]:
    base = resolve_commit(repo, base)
    original = _tree(repo, base)
    current: dict[str, tuple[str, str]] = {}
    contents: dict[str, bytes] = {}
    algorithm = run_git(repo, "rev-parse", "--show-object-format")
    index: dict[str, tuple[str, str]] = {}
    for entry in _git(repo, "ls-files", "--stage", "-z").split(b"\0"):
        if not entry:
            continue
        metadata, raw_path = entry.split(b"\t", 1)
        mode, oid, stage = metadata.decode().split()
        if stage != "0" or mode == "160000":
            raise ValueError("unmerged indexes and submodules cannot be published")
        index[os.fsdecode(raw_path)] = (mode, oid)
    if staged:
        current = index
    else:
        names = set(
            _git(repo, "ls-files", "-z", "--cached", "--others", "--exclude-standard").split(b"\0")
        )
        for raw_path in sorted(names - {b""}):
            path = os.fsdecode(raw_path)
            target = repo / path
            # Do not follow parent symlinks outside the checkout.
            if not target.parent.resolve().is_relative_to(repo.resolve()):
                raise ValueError("snapshot path escapes repository")
            try:
                info = target.lstat()
            except FileNotFoundError:
                continue
            if stat.S_ISLNK(info.st_mode):
                content = os.fsencode(os.readlink(target))
                mode = "120000"
            elif stat.S_ISREG(info.st_mode):
                content = target.read_bytes()
                mode = "100755" if info.st_mode & stat.S_IXUSR else "100644"
            else:
                raise ValueError("snapshot requires regular files or symlinks")
            current[path] = (mode, _blob(content, algorithm))
            contents[path] = content
    paths = sorted(
        path for path in original.keys() | current.keys() if original.get(path) != current.get(path)
    )
    manifest = [
        {
            "path": path,
            "before": list(original[path]) if path in original else None,
            "after": list(current[path]) if path in current else None,
        }
        for path in paths
    ]
    digest = hashlib.sha256(
        json.dumps(
            {"base": base, "files": manifest}, sort_keys=True, separators=(",", ":")
        ).encode()
    ).hexdigest()
    changes = []
    if not staged:
        for path in paths:
            before = _git(repo, "cat-file", "blob", original[path][1]) if path in original else b""
            after = contents.get(path, b"")
            patch = "".join(
                difflib.unified_diff(
                    before.decode("utf-8", "replace").splitlines(True),
                    after.decode("utf-8", "replace").splitlines(True),
                    fromfile="a/" + path,
                    tofile="b/" + path,
                )
            )
            if not patch:
                patch = f"mode or binary content changed: {path}"
            changes.append(ChangedFile(path=path, patch=patch))
    if not staged:
        # Include index-only changes in scope/risk inspection even when the
        # worktree restores the base content. Publication still compares the
        # final content digest with the staged content digest exactly.
        for path in sorted(original.keys() | index.keys()):
            if index.get(path) in (original.get(path), current.get(path)):
                continue
            before = _git(repo, "cat-file", "blob", original[path][1]) if path in original else b""
            after = _git(repo, "cat-file", "blob", index[path][1]) if path in index else b""
            patch = (
                "".join(
                    difflib.unified_diff(
                        before.decode("utf-8", "replace").splitlines(True),
                        after.decode("utf-8", "replace").splitlines(True),
                        fromfile="a/" + path,
                        tofile="b/" + path,
                    )
                )
                or f"staged mode or binary content changed: {path}"
            )
            changes.append(ChangedFile(path=path, patch=patch))
    return {
        "digest": digest,
        "changed_files": sorted(set(paths) | {change.path for change in changes}),
        "manifest": manifest,
        "base_reference": base,
        "head_reference": resolve_commit(repo, "HEAD"),
        "branch": run_git(repo, "branch", "--show-current"),
    }, tuple(changes)
