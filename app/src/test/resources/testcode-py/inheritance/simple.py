"""Simple single-level inheritance test cases."""


class Animal:
    """Base class for animals."""

    def speak(self):
        """Make a sound."""
        pass


class Dog(Animal):
    """A dog that extends Animal."""

    def bark(self):
        """Bark like a dog."""
        return "woof"


class Cat(Animal):
    """A cat that extends Animal."""

    def meow(self):
        """Meow like a cat."""
        return "meow"
