public class MultipleHitsInSameMethod {
    public void caller(Foo foo) {
        // Two calls to the SAME method in the same enclosing method
        foo.process();
        foo.process();
    }
}
