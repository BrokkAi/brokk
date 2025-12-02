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


# Try/except pattern (common for optional dependencies)
try:
    class TryClass:
        """Class defined in try block."""
        pass

    def try_function():
        """Function defined in try block."""
        return "try"

    TRY_VAR = "try_value"
except ImportError:
    class ExceptClass:
        """Fallback class in except block."""
        pass

    def except_function():
        """Fallback function in except block."""
        return "except"

    EXCEPT_VAR = "except_value"


# With statement pattern (less common but valid)
with open(__file__) if False else type("ctx", (), {"__enter__": lambda s: s, "__exit__": lambda *a: None})():
    WITH_VAR = "with_value"
