public class OverloadsUser {

    public void useOverloads() {
        var o = new Overloads();

        // Call different overloads
        o.process();
        o.process(42);
        o.process("hello");
        o.process(1, "world");
    }

    public void useArrayOverloads() {
        var o = new Overloads();
        o.handle(new int[]{1, 2, 3});
        o.handle(new String[]{"a", "b"});
    }
}
