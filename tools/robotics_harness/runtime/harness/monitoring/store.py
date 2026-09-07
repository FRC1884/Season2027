"""Append-only JSON Lines event persistence."""

from __future__ import annotations

import json
import os
from pathlib import Path

from harness.monitoring.models import MonitoringEvent


class EventStoreError(RuntimeError):
    """Raised when persisted monitoring evidence cannot be decoded."""


class EventStore:
    """A transparent local event store suitable for prototypes and tests."""

    def __init__(self, path: Path | str) -> None:
        self.path = Path(path)

    def append(self, event: MonitoringEvent) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        if self.path.is_symlink():
            raise EventStoreError("event store path cannot be a symbolic link")
        payload = json.dumps(event.to_dict(), sort_keys=True, separators=(",", ":"))
        flags = os.O_WRONLY | os.O_CREAT | os.O_APPEND
        if hasattr(os, "O_NOFOLLOW"):
            flags |= os.O_NOFOLLOW
        descriptor = os.open(
            self.path,
            flags,
            0o600,
        )
        os.chmod(self.path, 0o600)
        with os.fdopen(descriptor, "a", encoding="utf-8") as stream:
            stream.write(payload)
            stream.write("\n")
            stream.flush()

    def read_all(self) -> tuple[MonitoringEvent, ...]:
        if not self.path.exists():
            return ()
        events: list[MonitoringEvent] = []
        for line_number, line in enumerate(
            self.path.read_text(encoding="utf-8").splitlines(), start=1
        ):
            if not line.strip():
                continue
            try:
                value = json.loads(line)
                if not isinstance(value, dict):
                    raise ValueError("event must be an object")
                events.append(MonitoringEvent.from_dict(value))
            except (json.JSONDecodeError, TypeError, ValueError) as error:
                raise EventStoreError(
                    f"invalid event at {self.path}:{line_number}: {error}"
                ) from error
        return tuple(events)
