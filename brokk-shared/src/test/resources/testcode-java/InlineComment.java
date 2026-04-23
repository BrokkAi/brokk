public class InlineComment {
    private int field = 0;

    /** Regular Javadoc on its own line */
    public void regularMethod() {
        System.out.println("Regular");
    }

    private int other = 1; /** Inline Javadoc on same line as code */
    public void methodAfterInlineJavadoc() {
        System.out.println("After inline");
    }
}
