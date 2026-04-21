# Test that function redefinition and imports don't interfere with each other
# Regression guard: "last wins" for defs, imports tracked separately

from os.path import join as my_function  # Import with same name as function

def my_function():
    """First definition - should be replaced"""
    class FirstLocal:
        pass
    return "first"


def my_function():
    """Second definition - this should be the retained one"""
    class SecondLocal:
        """This local class should be attached to the final my_function"""
        pass
    return "second"


class MyClass:
    """Regular class for comparison"""
    pass

# Also test that a different import doesn't affect unrelated function

def other_function():
    """This should exist independently"""
    pass
