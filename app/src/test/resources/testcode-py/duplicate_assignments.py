# Test file for Python's "last assignment wins" behavior for module-level variables

# First assignments
CONFIG = "initial_config"
VERSION = "1.0.0"
DEBUG = True

# Some other code
def some_function():
    pass

class SomeClass:
    pass

# Duplicate assignments (should override previous ones)
CONFIG = "final_config"
VERSION = "2.0.0"
DEBUG = False

# More duplicates to test multiple overrides
CONFIG = "another_config"  # This should be the final value
VERSION = "3.0.0"  # This should be the final value

# Final state should be:
# CONFIG = "another_config"
# VERSION = "3.0.0" 
# DEBUG = False