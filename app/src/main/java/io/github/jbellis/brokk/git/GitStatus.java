package io.github.jbellis.brokk.git;

/**
 * Represents the status of a file in a git diff.
 */
public enum GitStatus {
    /**
     * Status is not yet determined (placeholder during streaming)
     */
    UNKNOWN,
    
    /**
     * File was added (A)
     */
    ADDED,
    
    /**
     * File was modified (M)
     */
    MODIFIED,
    
    /**
     * File was deleted (D)
     */
    DELETED
}
