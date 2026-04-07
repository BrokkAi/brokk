public class UseE {
    // Field of type E (tests usage of E as a type)
    E e = new E();

    public void some() {
        // References to E's static field and static method
        E.sField = 123;
        E.sMethod();
    }

    public void moreM() {
        e.iMethod();
    }

    public void moreF() {
        System.out.println(e.iField);
    }

    // Added for ambiguity test: method call to foo
    public void callFoo() {
        ServiceImpl s = new ServiceImpl("x");
        s.foo();
    }

    // Added for ambiguity test: field assignment to foo
    public void assignFoo() {
        e.foo = 1;
    }
}
