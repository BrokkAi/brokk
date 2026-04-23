"""Nested module that imports from grandparent directory."""

from ...base import BaseClass


class NestedClass(BaseClass):
    """A deeply nested class that extends BaseClass from grandparent directory."""

    def nested_method(self):
        """A method in the nested class."""
        return "nested"
