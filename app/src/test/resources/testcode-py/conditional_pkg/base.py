"""Minimal reproduction of Reflex base.py pattern with conditional class definitions."""

# Simulating: if find_spec("pydantic"):
if True:
    class Base:
        """The base class when condition is true."""

        class Config:
            """Nested config class."""
            arbitrary_types_allowed = True

        def __init__(self):
            """Initialize base."""
            pass

        def to_json(self) -> str:
            """Convert to JSON."""
            return "{}"

else:
    class FallbackBase:
        """Fallback when condition is false."""

        def __init__(self):
            raise ImportError("Not available")

    Base = FallbackBase


# Top-level function inside conditional (should this be captured?)
if True:
    def conditional_function():
        """A function defined inside an if block."""
        return "conditional"


# Top-level variable inside conditional (should this be captured?)
if True:
    CONDITIONAL_VAR = "value"
