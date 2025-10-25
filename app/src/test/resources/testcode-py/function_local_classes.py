# Test file for function-local class handling

def test_minimal_subclass():
    """Function containing a local class - similar to astropy pattern"""
    class LogDRepresentation:
        """Local class that should not cause duplicate detection errors"""
        def format(self):
            return "formatted"
    
    return LogDRepresentation

def another_function():
    """Another function with same local class name"""
    class LogDRepresentation:
        """Different local class with same name - should not conflict"""
        def format(self):
            return "different"
    
    return LogDRepresentation

def test_minimal_subclass_duplicate():
    """Same function name with another local class"""
    class LogDRepresentation:
        """This should create the same FQN pattern as the first one"""
        def format(self):
            return "duplicate"
    
    return LogDRepresentation

# Top-level class for comparison
class TopLevelRepresentation:
    """This should be processed normally"""
    def format(self):
        return "top_level"