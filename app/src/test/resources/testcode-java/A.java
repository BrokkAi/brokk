import java.util.function.Function;

public class A {
    public void method1() {
        System.out.println("hello");
    }

    public String method2(String input) {
        return "prefix_" + input;
    }

    public String method2(String input, int otherInput) {
        // overload of method2
        return "prefix_" + input + " " + otherInput;
    }

    public Function<Integer, Integer> method3() {
        return x -> x + 1;
    }

    public static int method4(double foo, Integer bar) {
        return 0;
    }

    public void method5() {
        // self-reference
        System.out.println(new A());
    }

    public void method6() {
        // nested self-reference
        new Runnable() {
            public void run() {
                System.out.println(new A());
            }
        }.run();
    }

    public class AInner {
        public class AInnerInner {
            public void method7() {
                System.out.println("hello");
            }
        }
    }

    public static class AInnerStatic {}
}
