"""Multiple inheritance test cases."""


class Flyable:
    """Mixin for flying capability."""

    def fly(self):
        return "flying"


class Swimmable:
    """Mixin for swimming capability."""

    def swim(self):
        return "swimming"


class Duck(Flyable, Swimmable):
    """A duck that can both fly and swim."""

    def quack(self):
        return "quack"
