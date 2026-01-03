"""Test file to verify Python class usage patterns"""

class BaseClass:
    @staticmethod
    def static_method():
        return "static"

    def instance_method(self):
        return "instance"


class ExtendedClass(BaseClass):
    """Class extending BaseClass"""

    def __init__(self, param: BaseClass):
        super().__init__()
        # Constructor call
        self.field: BaseClass = BaseClass()
        # Static access
        result = BaseClass.static_method()

    # Type hints
    def process_base(self, input: BaseClass) -> BaseClass:
        return input

    # Return type annotation
    def get_instance(self) -> BaseClass:
        return self.field


# Variable with type hint
my_base: BaseClass = BaseClass()

# Import pattern
from typing import List
instances: List[BaseClass] = []
