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


# elif clause pattern
if False:
    ELIF_IF_VAR = "if_branch"
elif True:
    def elif_function():
        """Function defined in elif block."""
        return "elif"

    ELIF_VAR = "elif_value"
else:
    ELIF_ELSE_VAR = "else_branch"


# try-else pattern (runs when no exception)
try:
    TRY_ELSE_SETUP = "setup"
except Exception:
    TRY_ELSE_EXCEPT = "exception"
else:
    def try_else_function():
        """Function defined in try-else block."""
        return "try_else"

    TRY_ELSE_VAR = "try_else_value"


# try-finally pattern (always runs)
try:
    TRY_FINALLY_SETUP = "setup"
finally:
    def finally_function():
        """Function defined in finally block."""
        return "finally"

    FINALLY_VAR = "finally_value"
