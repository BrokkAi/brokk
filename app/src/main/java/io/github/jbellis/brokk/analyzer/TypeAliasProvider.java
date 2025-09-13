package io.github.jbellis.brokk.analyzer;

/** Capability for analyzers that can identify whether a CodeUnit represents a type alias. */
public interface TypeAliasProvider extends CapabilityProvider {
    /** Returns true if the given CodeUnit represents a type alias in the underlying language. */
    boolean isTypeAlias(CodeUnit cu);
}
