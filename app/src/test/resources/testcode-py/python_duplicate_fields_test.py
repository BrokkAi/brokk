# === Python Duplicate Fields Test File ===
# This file consolidates test patterns for Python duplicate field handling
# Tests the "last assignment wins" behavior for module-level variables

# Section 1: Astropy Pattern (from astropy_duplicate_fields.py)
# Tests multiple assignments to same variable at module level
# This reproduces the original astropy/stats/setup_package.py issue
SRCFILES = ['file1.py', 'file2.py']
SRCFILES = ['file1.py', 'file2.py', 'file3.py']  # Should override previous assignment

# Another variable with multiple assignments
VERSION = "1.0"
VERSION = "1.1"  # Should override previous assignment

# Section 2: Original Issue Pattern (from duplicate_fields.py)  
# Tests different scopes to ensure only module-level duplicates are handled
def some_function():
    LOCAL_VAR = ['file3.py', 'file4.py']  # Function scope - no conflict
    return LOCAL_VAR

class SomeClass:
    CLASS_VAR = ['file5.py', 'file6.py']  # Class scope - no conflict
    
    def method(self):
        METHOD_VAR = ['file7.py', 'file8.py']  # Method scope - no conflict
        return METHOD_VAR

# Section 3: Additional test constructs
# These should not be affected by deduplication
class TestClass:
    pass

def test_function():
    pass

OTHER_VAR = "different"  # No duplicates - should remain