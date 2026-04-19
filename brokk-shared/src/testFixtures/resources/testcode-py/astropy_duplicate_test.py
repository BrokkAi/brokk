# Test file matching astropy pattern
# Note: In real astropy, duplicate function names occur across different files
# Here we test different function names to verify basic functionality

def test_minimal_subclass():
    """Function containing a local class - astropy pattern"""
    class LogDRepresentation:
        """Local class that should be scoped to function"""
        def format(self):
            return "formatted"
    
    return LogDRepresentation

def another_test_function():
    """Another function with same local class name"""
    class LogDRepresentation:
        """Different local class with same name - should not conflict"""
        def format(self):
            return "different"
    
    return LogDRepresentation

# Top-level class for comparison
class TopLevelRepresentation:
    """This should be processed normally"""
    def format(self):
        return "top_level"