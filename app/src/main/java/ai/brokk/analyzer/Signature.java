package ai.brokk.analyzer;

import org.jspecify.annotations.Nullable;

/**
 * Represents a function signature for disambiguating overloaded functions.
 * <p>
 * This sealed interface provides type-safe representation of signatures, making invalid
 * signatures unrepresentable through the type system.
 * <p>
 * Use {@link #none()} for non-function CodeUnits (classes, fields, etc.).
 * Use {@link #of(String)} for functions with a signature like "(type1, type2, ...)".
 * Use {@link #parse(String)} to convert nullable strings (for backward compatibility).
 */
public sealed interface Signature {

    /**
     * Factory method for absence of signature (for classes, fields, etc.)
     */
    static Signature none() {
        return None.INSTANCE;
    }

    /**
     * Factory method to create a signature from parameter types.
     * The format should be "(type1, type2, ...)" or "()" for zero parameters.
     *
     * @param params the parameter signature string
     * @return a Parameters signature
     * @throws IllegalArgumentException if params is null, empty, or malformed
     */
    static Signature of(String params) {
        if (params == null) {
            throw new IllegalArgumentException("Use Signature.none() for null signatures");
        }
        if (params.isEmpty()) {
            throw new IllegalArgumentException("Use Signature.none() for empty signatures");
        }
        return new Parameters(params);
    }

    /**
     * Parse a nullable string into a Signature.
     * Null or empty strings map to none().
     *
     * @param s the string to parse, or null
     * @return Signature.none() if s is null/empty, otherwise Signature.of(s)
     */
    static Signature parse(@Nullable String s) {
        return (s == null || s.isEmpty()) ? none() : of(s);
    }

    /**
     * Singleton representing absence of a signature.
     */
    final class None implements Signature {
        static final None INSTANCE = new None();

        private None() {
            // Private constructor - use Signature.none() factory method
        }

        @Override
        public String toString() {
            return "Signature.None";
        }
    }

    /**
     * Represents a function signature with parameters.
     */
    record Parameters(String value) implements Signature {}

    /**
     * Check if this signature represents absence of a signature.
     */
    default boolean isEmpty() {
        return this instanceof None;
    }

    /**
     * Get the string value of this signature.
     * Returns empty string for None, the parameter string for Parameters.
     */
    default String value() {
        return switch (this) {
            case None ignored -> "";
            case Parameters(var value) -> value;
        };
    }
}
