"""Shared event-normalization utilities for JobEvent payloads."""

from __future__ import annotations

from typing import Any


def normalize_event_data(event: dict[str, Any]) -> dict[str, Any]:
    """Normalize the ``data`` field of a JobEvent into a dict.

    - dict payloads are returned unchanged
    - string payloads (used by NOTIFICATION / ERROR events) are wrapped as
      ``{"message": <string>}``
    - all other payload types return an empty dict
    """
    raw = event.get("data")
    if isinstance(raw, dict):
        return raw
    if isinstance(raw, str):
        return {"message": raw}
    return {}


_FAILURE_STATES = frozenset({"FAILED", "CANCELLED"})


def is_failure_state(state: str) -> bool:
    """Return True if *state* indicates the job ended unsuccessfully."""
    return state in _FAILURE_STATES
