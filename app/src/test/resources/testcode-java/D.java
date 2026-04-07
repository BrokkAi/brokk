public class D {
    public static int field1;
    private String field2;
    
    public void methodD1() {
        A a = new A();
        B b = new B();
        a.method1();
        b.callsIntoA();
    }
    
    public void methodD2() {
        methodD1();
        field1 = 42;
    }

    private static class DSubStatic {}

    private class DSub {}
}

