# Test file for non-standard Python naming conventions
# Tests the isLowercaseIdentifier heuristic used for distinguishing functions from classes

# Case 1: Uppercase function names (violates PEP 8, but valid Python)
# These should ideally be treated as functions, but the heuristic sees them as classes
def HTTPServer():
    """Function with all-uppercase acronym - looks like a class"""
    class ServerHandler:
        """Class inside HTTPServer function"""
        def handle(self):
            pass
    return ServerHandler()

def GetData():
    """Function with PascalCase name - looks like a class"""
    return {}

def URL():
    """Single all-caps function"""
    pass

# Case 2: Lowercase class names (violates PEP 8, but valid Python)
# These should ideally be treated as classes, but the heuristic sees them as functions
class myClass:
    """Class with camelCase starting lowercase"""
    def method(self):
        pass

class my_class:
    """Class with snake_case name - looks like a function"""
    class Nested:
        """Nested class inside lowercase parent"""
        def nested_method(self):
            pass
    def my_method(self):
        pass

class _privateClass:
    """Private class with lowercase after underscore"""
    def internal_method(self):
        pass

# Case 3: Mixed scenarios - function-local classes with non-standard names
def createFactory():
    """PascalCase function containing classes"""
    class product:
        """Lowercase class inside PascalCase function"""
        def build(self):
            pass
    return product()

def process_data():
    """Normal function with PascalCase local class"""
    class DataProcessor:
        """Normal PascalCase class"""
        def process(self):
            pass
    return DataProcessor()

# Case 4: Edge cases
class _:
    """Single underscore class name"""
    pass

class __:
    """Double underscore class name"""
    pass

def _():
    """Single underscore function name (would override class above)"""
    pass

class XMLParser:
    """Normal PascalCase class for comparison"""
    def parse(self):
        pass
