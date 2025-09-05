import io.github.jbellis.brokk.Foo;

public class UsePackaged {
    public void callPackagedMethod() {
        Foo foo = new Foo();
        foo.bar();
    }
}