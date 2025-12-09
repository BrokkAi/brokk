"""Sibling module that imports from same directory."""

from .child import ChildClass


class SiblingClass:
    """A class that uses ChildClass from the same directory."""

    def __init__(self):
        self.child = ChildClass()

    def sibling_method(self):
        """A method in the sibling class."""
        return "sibling"
