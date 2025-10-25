class MplTimeConverter:
    """Test class with property getter and setter"""

    @property
    def format(self):
        """Property getter - should be captured"""
        return self._format

    @format.setter
    def format(self, value):
        """Property setter - should be skipped"""
        self._format = value

    def regular_method(self):
        """Regular method - should be captured"""
        pass
