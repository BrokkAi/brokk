def test_function_1():
    class LocalClass:
        def method1(self):
            pass
    
    instance = LocalClass()
    return instance

def test_function_2():
    class LocalClass:
        def method2(self):
            pass
    
    instance = LocalClass()
    return instance

def test_function_3():
    class LocalClass:
        def method3(self):
            pass
    
    instance = LocalClass()
    return instance

# Top-level class for comparison
class TopLevelClass:
    def top_method(self):
        pass