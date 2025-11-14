# Test Python function redefinition - "last wins" semantics

def my_function():
    """First definition"""
    class FirstLocal:
        pass
    return "first"

def my_function():
    """Second definition - this should be the retained one"""
    class SecondLocal:
        pass
    return "second"

class MyClass:
    """Regular class for comparison"""
    pass
