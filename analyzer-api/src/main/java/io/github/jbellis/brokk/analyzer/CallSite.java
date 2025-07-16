package io.github.jbellis.brokk.analyzer;

/**
 * A record representing a call site in source code.
 *
 * @param target The CodeUnit of the method being called/calling
 * @param sourceLine The actual source line where the call occurs
 */
public record CallSite(CodeUnit target, String sourceLine) implements Comparable<CallSite> {
    @Override
    public int compareTo(CallSite other) {
        return target.identifier().compareTo(other.target.identifier());
    }
}
