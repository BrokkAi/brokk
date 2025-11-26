"""Multi-level inheritance test cases."""


class Base:
    """The root base class."""

    def base_method(self):
        pass


class Middle(Base):
    """Middle class extending Base."""

    def middle_method(self):
        pass


class Child(Middle):
    """Child class extending Middle."""

    def child_method(self):
        pass
