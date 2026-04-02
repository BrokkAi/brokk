# Test Python function naming with underscores

def _private_function():
    """Private function starting with underscore"""
    class LocalClass:
        pass
    return LocalClass

def __dunder_function__():
    """Dunder function"""
    class AnotherLocal:
        pass
    return AnotherLocal

class MyClass:
    """Regular class for comparison"""
    pass

class _PrivateClass:
    """Private class - should NOT be treated as function-local"""
    class NestedClass:
        pass
