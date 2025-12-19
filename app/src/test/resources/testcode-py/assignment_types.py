# Test file for various Python assignment patterns

# Simple assignment (already captured)
SIMPLE = 1

# Annotated assignment with value (PEP 526)
ANNOTATED: int = 2

# Type-only annotation (no value)
TYPED_ONLY: str

# Tuple unpacking
TUPLE_A, TUPLE_B = 1, 2

# Multi-target assignment (same value to multiple names)
MULTI_1 = MULTI_2 = 42

# Nested assignment in multi-target
CHAIN_A = CHAIN_B = CHAIN_C = "chained"

# Inside conditional block
if True:
    COND_SIMPLE = 10
    COND_ANNOTATED: int = 20
    COND_TUPLE_A, COND_TUPLE_B = 30, 40


# Class with annotated attributes (should NOT be module-level)
class MyClass:
    class_attr: int = 100
    a, b = 1, 2

    def __init__(self):
        self.instance_attr: str = "hello"

# Function with local assignments (should NOT be module-level)
def my_function():
    local_var: int = 999
    x, y = 1, 2
    return local_var
