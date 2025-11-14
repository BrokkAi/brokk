package ai.brokk.analyzer.usages;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;

/**
 * Immutable metadata describing a usage occurrence.
 *
 * <p>Equality and hashing are based on the enclosing CodeUnit only. This allows sets/maps of UsageHit to deduplicate by
 * the logical usage context (the enclosing unit), regardless of specific offsets, line numbers, or snippet text.
 *
 * @param file the file containing the usage
 * @param line 1-based line number
 * @param startOffset character start offset within the file content
 * @param endOffset character end offset within the file content
 * @param enclosing best-effort enclosing CodeUnit for the usage
 * @param confidence [0.0, 1.0], 1.0 for exact/unique matches; may be lower when disambiguated
 * @param snippet short text snippet around the usage location
 */
public record UsageHit(
        ProjectFile file,
        int line,
        int startOffset,
        int endOffset,
        CodeUnit enclosing,
        double confidence,
        String snippet) {
    public UsageHit withConfidence(double confidence) {
        return new UsageHit(file, line, startOffset, endOffset, enclosing, confidence, snippet);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UsageHit that)) return false;
        return enclosing.equals(that.enclosing);
    }

    @Override
    public int hashCode() {
        return enclosing.hashCode();
    }
}
