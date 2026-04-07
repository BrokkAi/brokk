def funcA():
    # outside of class
    print("hello")

class A:
    def __init__(self):
        self.x = 0
        
    def method1(self) -> None:
        print("hello")

    def method2(self, input_str: str, other_input: int = None) -> str:
        if other_input is None:
            return f"prefix_{input_str}"
        return f"prefix_{input_str} {other_input}"

    def method3(self) -> Callable[[int], int]:
        return lambda x: x + 1

    @staticmethod
    def method4(foo: float, bar: int) -> int:
        return 0

    def method5(self) -> None:
        # instantiation
        print(A())

    def method6(self) -> None:
        # nested method
        def run():
            print(A())
        run()
