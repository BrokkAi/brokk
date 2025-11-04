package ai.brokk.gui.visualize;

/**
 * Minimal generic Pair utility to use as a map key.
 */
public record Pair<A, B>(A first, B second) {
    public static <A, B> Pair<A, B> of(A a, B b) {
        return new Pair<>(a, b);
    }
}
