package ai.brokk.analyzer;

import org.jspecify.annotations.Nullable;

/**
 * Represents a function signature for disambiguating overloaded functions.
 * <p>
 * A signature includes parameter types in the format: "(type1, type2, ...)"
 * For functions without parameters, the signature is "()".
 * The NONE constant represents absence of a signature (e.g., for non-function CodeUnits).
 */
public record Signature(String value) {
    /**
     * Constant representing no signature (for classes, fields, etc.)
     */
    public static final Signature NONE = new Signature("");

    /**
     * Parse a nullable string into a Signature.
     * Null or empty strings map to NONE.
     */
    public static Signature parse(@Nullable String s) {
        if (s == null || s.isEmpty()) {
            return NONE;
        }
        return new Signature(s);
    }

    /**
     * Check if this signature represents absence of a signature
     */
    public boolean isEmpty() {
        return value.isEmpty();
    }

    /**
     * Get the nullable string representation (for backward compatibility)
     */
    public @Nullable String toNullableString() {
        return isEmpty() ? null : value;
    }
}
