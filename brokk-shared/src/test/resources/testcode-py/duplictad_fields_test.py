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

# Section 4: Property Pattern Test (from MplTimeConverter)
# Tests @property/@setter pattern to ensure setters are skipped for duplicate detection
class MplTimeConverter:
    """Test class for property getter/setter pattern"""

    @property
    def time(self):
        """Property getter"""
        return self._time

    @time.setter
    def time(self, value):
        """Property setter - should be skipped for duplicate detection"""
        self._time = value

    @property
    def date(self):
        """Another property getter"""
        return self._date

    @date.setter
    def date(self, value):
        """Another property setter - should be skipped for duplicate detection"""
        self._date = value

# Section 5: Property Setter Detection Test
# Tests accurate property setter detection vs regular functions

class PropertyTest:
    @property
    def value(self):
        """Property getter - should be processed"""
        return self._value
    
    @value.setter
    def value(self, val):
        """Property setter - should be skipped"""
        self._value = val
    
    @property
    def name(self):
        """Another property getter"""
        return self._name
    
    @name.setter
    def name(self, val):
        """Another property setter - should be skipped"""
        self._name = val

# Regular functions that should NOT be treated as property setters
def test_uncertainty_setter():
    """Test function - should be processed (not a property setter)"""
    pass

def set_temperature():
    """Regular function with set_ prefix - should be processed"""
    pass

def process_data_setter():
    """Regular function ending with _setter - should be processed"""
    pass

# Edge case: decorator that looks like setter but isn't
def not_real_setter():
    """Regular function - should be processed"""
    pass
