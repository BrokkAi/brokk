"""A file that imports and extends Base."""

from conditional_pkg.base import Base


class MySubclass(Base):
    """A class that extends Base."""

    def custom_method(self):
        """Custom method in subclass."""
        return "custom"


class AnotherSubclass(Base):
    """Another class extending Base."""

    value: int = 42
