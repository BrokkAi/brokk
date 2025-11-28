"""Cross-file inheritance test - imports from simple.py."""

from inheritance.simple import Animal


class Bird(Animal):
    """A bird that extends Animal from another file."""

    def fly(self):
        """Fly like a bird."""
        return "flap flap"
