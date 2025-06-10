package io.github.jbellis.brokk.issues;

/**
 * Marker interface for issue filter options.
 * Specific implementations (records) will define the actual filter fields.
 */
public interface FilterOptions {
    // Common fields could be defined here if applicable to ALL implementations.
    // For now, it's a marker, and services will cast to concrete types.

    /**
     * @return The text query for searching issues, or null if no text search is applied.
     */
    default String query() {
        return null;
    }
}
