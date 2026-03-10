"""Shared event utilities for JobEvent payloads."""

from __future__ import annotations


_FAILURE_STATES = frozenset({"FAILED", "CANCELLED"})


def is_failure_state(state: str) -> bool:
    """Return True if *state* indicates the job ended unsuccessfully."""
    return state in _FAILURE_STATES
