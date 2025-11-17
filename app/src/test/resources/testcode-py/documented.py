"""
Module docstring for documented.py
This module demonstrates Python comment and docstring capture.
"""

# Global variable with comment
GLOBAL_CONSTANT = "test_value"

def standalone_function(param):
    """
    Standalone function with docstring.

    Args:
        param: A parameter for testing

    Returns:
        str: The processed result
    """
    return f"processed: {param}"

# Comment before class
class DocumentedClass:
    """
    A comprehensive test class with various documentation patterns.
    This class demonstrates how the analyzer handles:
    - Class-level docstrings
    - Method docstrings
    - Comments before methods
    """

    # Comment before constructor
    def __init__(self, value: int):
        """
        Initialize the documented class.

        Args:
            value: Initial value for the instance
        """
        self.value = value

    # Comment before instance method
    def get_value(self):
        """
        Get the current value.

        Returns:
            int: The current value
        """
        return self.value

    # Comment before static method
    @staticmethod
    def utility_method(data):
        """
        A static utility method.

        Args:
            data: Data to process

        Returns:
            str: Processed data
        """
        return str(data).upper()

    # Comment before class method
    @classmethod
    def create_default(cls):
        """
        Create a default instance.

        Returns:
            DocumentedClass: A new instance with default values
        """
        return cls(0)

# Comment before nested class
class OuterClass:
    """Outer class with nested class."""

    # Comment before nested class
    class InnerClass:
        """
        Inner class documentation.
        This demonstrates nested class handling.
        """

        # Comment before inner method
        def inner_method(self):
            """Inner method with documentation."""
            return "inner result"