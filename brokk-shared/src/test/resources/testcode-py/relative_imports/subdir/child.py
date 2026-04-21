"""Child module that imports from parent directory."""

from ..base import BaseClass


class ChildClass(BaseClass):
    """A child class that extends BaseClass from parent directory."""

    def child_method(self):
        """A method in the child class."""
        return "child"
