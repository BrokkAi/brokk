package ai.brokk.analyzer;

import org.jspecify.annotations.NullMarked;

/**
 * Comment line counts and span lines for a {@link CodeUnit}, with optional roll-up of nested declarations
 * (e.g. methods inside a class).
 */
@NullMarked
public record CommentDensityStats(
        String fqName,
        String relativePath,
        int headerCommentLines,
        int inlineCommentLines,
        /** Lines covered by this declaration's ranges (may sum overload ranges). */
        int spanLines,
        /** Header lines including nested declarations (for class-like units equals own plus children). */
        int rolledUpHeaderCommentLines,
        /** Inline lines including nested declarations. */
        int rolledUpInlineCommentLines,
        /** Span lines including nested declarations (sum of descendant spans for roll-up). */
        int rolledUpSpanLines) {}
