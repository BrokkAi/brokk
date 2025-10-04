package io.github.jbellis.brokk.analyzer.usages;

import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.ProjectFile;

/**
 * Immutable metadata describing a usage occurrence.
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
}
