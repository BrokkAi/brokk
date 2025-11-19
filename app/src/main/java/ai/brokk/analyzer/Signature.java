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
     * @param params the parameter signature string, or null
     * @return Signature.none() if params is null/empty, otherwise a Parameters signature
     */
    static Signature of(@Nullable String params) {
        if (params == null || params.isEmpty()) {
            return none();
        }
        return new Parameters(params);
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
