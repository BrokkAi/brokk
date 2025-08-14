public class C {
    record Foo(int x) {
        public Foo {
            System.out.println("Foo constructor");
        }
    }
}