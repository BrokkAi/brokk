# Test Python "last wins" semantics for duplicate children

class TestDuplicates:
    """Class with various types of duplicate children"""
    
    # Duplicate class attributes (fields)
    name = "first"
    age = 10
    name = "second"  # Python: last wins - this should be the retained value
    
    # Duplicate method definitions
    def method(self):
        """First definition"""
        return 1
    
    def method(self):
        """Second definition - should be retained (last wins)"""
        return 2
    
    # Duplicate nested classes
    class Inner:
        """First nested class"""
        x = 1
    
    class Inner:
        """Second nested class - should be retained (last wins)"""
        x = 2
        def inner_method(self):
            return "inner"
    
    # Non-duplicate for comparison
    def unique_method(self):
        return "unique"
    
    class UniqueInner:
        y = 3

# Top-level class for comparison
class RegularClass:
    """Regular class without duplicates"""
    value = 100
    
    def regular_method(self):
        return "regular"
