"""Shared event utilities for JobEvent payloads."""

from __future__ import annotations

from typing import Any

_FAILURE_STATES = frozenset({"FAILED", "CANCELLED"})


def safe_data(event: dict[str, Any]) -> dict[str, Any]:
    """Extract event data as a dict, coercing legacy string payloads to {}."""
    raw = event.get("data")
    return raw if isinstance(raw, dict) else {}


def is_failure_state(state: str) -> bool:
    """Return True if *state* indicates the job ended unsuccessfully."""
    return state in _FAILURE_STATES
