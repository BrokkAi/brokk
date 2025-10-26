class MplTimeConverter:
    """Test class with property getter, setter, and deleter"""

    @property
    def format(self):
        """Property getter - should be captured"""
        return self._format

    @format.setter
    def format(self, value):
        """Property setter - should be skipped"""
        self._format = value

    @property
    def value(self):
        """Property getter with deleter - should be captured"""
        return self._value

    @value.deleter
    def value(self):
        """Property deleter - should be skipped"""
        del self._value

    def regular_method(self):
        """Regular method - should be captured"""
        pass
