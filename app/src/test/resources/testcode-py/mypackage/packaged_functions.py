# Test file for packaged Python module with function-local classes

def my_function():
    """Top-level function in a package"""
    class LocalClass:
        """Class defined inside a function"""
        def method(self):
            return "method"
    return LocalClass()

def another_function():
    """Another top-level function"""
    class AnotherLocal:
        """Another local class"""
        pass
    return AnotherLocal()

class RegularClass:
    """Regular class at module level"""
    def regular_method(self):
        return "regular"
