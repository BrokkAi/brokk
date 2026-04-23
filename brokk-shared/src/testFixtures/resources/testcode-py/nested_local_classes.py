# Test nested classes inside function-local classes
def outer_function():
    class OuterLocal:
        class InnerLocal:
            class DeepLocal:
                pass

            def inner_method(self):
                pass

        def outer_method(self):
            pass

    return OuterLocal
