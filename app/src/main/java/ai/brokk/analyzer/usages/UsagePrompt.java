package ai.brokk.analyzer.usages;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds a single-usage prompt record for LLM-based relevance scoring.
 *
 * <p>The builder emits:
 *
 * <ul>
 *   <li>filterDescription: concise text describing the intended target
 *   <li>candidateText: the snippet representing this single usage
 *   <li>promptText: a Markdown-formatted block including file path, imports, and the code snippet
 * </ul>
 *
 * <p>A conservative token-to-character budget is enforced.
 */
public record UsagePrompt(String filterDescription, String candidateText, String promptText) {
    /**
     * Build a prompt for a single usage hit.
     */
    public static UsagePrompt build(
            UsageHit hit,
            CodeUnit codeUnitTarget,
            Collection<CodeUnit> alternatives,
            Collection<CodeUnit> polymorphicMatches,
            boolean hierarchySupported,
            IAnalyzer analyzer,
            String shortName,
            int maxTokens) {
        return build(
                List.of(hit),
                codeUnitTarget,
                alternatives,
                polymorphicMatches,
                hierarchySupported,
                analyzer,
                shortName,
                maxTokens);
    }

    /**
     * Build a prompt for multiple usage hits sharing the same enclosing CodeUnit.
     *
     * @param hits list of usage occurrences (must all share the same enclosing CodeUnit and file)
     * @param codeUnitTarget the intended target code unit
     * @param alternatives other code units with the same short name that are not the target
     * @param polymorphicMatches subclasses of the declaring class for the target method/field
     * @param hierarchySupported whether the analyzer supports type hierarchy resolution
     * @param analyzer used to retrieve import statements for the file containing the usage
     * @param shortName the short name being searched (e.g., "A.method2")
     * @param maxTokens rough token budget (approx 4 characters per token); non-positive to disable
     * @return UsagePrompt containing filterDescription, combined candidateText, and promptText
     */
    public static UsagePrompt build(
            List<UsageHit> hits,
            CodeUnit codeUnitTarget,
            Collection<CodeUnit> alternatives,
            Collection<CodeUnit> polymorphicMatches,
            boolean hierarchySupported,
            IAnalyzer analyzer,
            String shortName,
            int maxTokens) {
        if (hits.isEmpty()) {
            throw new IllegalArgumentException("hits must not be empty");
        }

        UsageHit first = hits.get(0);

        // Approximate token-to-character budget (very conservative)
        final int maxChars = (maxTokens <= 0) ? Integer.MAX_VALUE : Math.max(512, maxTokens * 4);
        var sb = new StringBuilder(Math.min(maxChars, 32_000));

        String polyInfo;
        if (!hierarchySupported) {
            polyInfo =
                    "\nNote: type hierarchy information is not available for this language, so polymorphic usages cannot be detected.";
        } else {
            polyInfo = polymorphicMatches.isEmpty()
                    ? ""
                    : "\nThe following subclasses are part of the inheritance hierarchy for this method, so calls on these types are also valid matches: %s"
                            .formatted(polymorphicMatches.stream()
                                    .map(CodeUnit::fqName)
                                    .collect(Collectors.joining(", ")));
        }

        // Filter description for RelevanceClassifier.relevanceScore
        String filterDescription =
                """
                Determine if the candidate snippet represents a usage of the %s %s, and not another symbol with the same name.
                Symbols with the same name (that we do NOT want to match) are: %s.%s
                Return a real number in [0.0, 1.0] representing your confidence that this snippet is referring to
                %s."""
                        .formatted(
                                codeUnitTarget.kind().name(),
                                codeUnitTarget.fqName(),
                                alternatives.isEmpty()
                                        ? "(none)"
                                        : alternatives.stream()
                                                .map(CodeUnit::fqName)
                                                .collect(Collectors.joining(", ")),
                                polyInfo,
                                codeUnitTarget.fqName());

        // Gather imports (best effort)
        List<String> imports;
        try {
            imports = analyzer.importStatementsOf(first.file());
        } catch (Throwable t) {
            imports = List.of(); // fail open
        }

        sb.append("<candidate filename=\"").append(first.file().getRelPath()).append("\">\n");

        sb.append("  <imports>\n");
        for (String imp : imports) {
            sb.append("    ").append(imp).append("\n");
        }
        sb.append("  </imports>\n");

        // Combine and deduplicate snippets
        String combinedSnippets = combineSnippets(hits);
        String snippetBlock =
                """
                  <snippet sourcemethod="%s">
                %s  </snippet>
                """
                        .formatted(first.enclosing().shortName(), combinedSnippets.indent(4));

        sb.append(snippetBlock);
        sb.append("</candidate>\n");

        String candidateText =
                """
                <candidate filename="%s">
                  <imports>
                %s  </imports>
                %s</candidate>
                """
                        .formatted(
                                first.file().getRelPath(),
                                imports.stream().map(s -> "    " + s + "\n").collect(Collectors.joining()),
                                snippetBlock);

        if (sb.length() > maxChars) {
            String marker = "\n... [truncated due to token limit]";
            int markerLength = marker.length();
            int safeLimit = Math.max(512, maxChars - markerLength);

            sb.setLength(safeLimit);

            sb.append(marker);
        }

        return new UsagePrompt(
                filterDescription, candidateText.trim(), sb.toString().trim());
    }

    /**
     * Merges snippets from multiple hits into a single block.
     *
     * <p>This algorithm detects overlap based on line-number arithmetic, assuming each snippet
     * provides approximately 3 lines of context above and below the usage line. When snippets
     * overlap, lines already covered by the previous snippet are skipped to avoid duplication.
     */
    private static String combineSnippets(List<UsageHit> hits) {
        if (hits.size() == 1) {
            return hits.get(0).snippet();
        }

        // Sort hits by line number
        List<UsageHit> sortedHits = hits.stream()
                .sorted((a, b) -> Integer.compare(a.line(), b.line()))
                .toList();

        StringBuilder result = new StringBuilder();
        int lastEndLine = -1;

        for (UsageHit hit : sortedHits) {
            String currentSnippet = hit.snippet();
            int currentLine = hit.line();
            // Snippets have ~3 lines above/below context.
            int currentStartLine = Math.max(1, currentLine - 3);
            int currentEndLine = currentLine + 3;

            if (lastEndLine != -1 && currentStartLine <= lastEndLine) {
                // Overlap or contiguous: calculate lines to skip in the current snippet
                String[] currentLines = currentSnippet.split("\n", -1);
                int linesInSnippet = currentLines.length;

                // How many lines from the start of this snippet are already covered?
                // The snippet represents lines [currentStartLine, currentStartLine + linesInSnippet - 1].
                // Lines up to lastEndLine are already in the result.
                int linesToSkip = lastEndLine - currentStartLine + 1;

                // Ensure we don't skip more lines than the snippet has, and always include
                // at least the line containing the actual hit (which is near the middle of the snippet).
                // The hit line is at index (currentLine - currentStartLine) in the snippet array.
                int hitLineIndex = currentLine - currentStartLine;
                // Ensure we include at least from the hit line onward
                linesToSkip = Math.min(linesToSkip, hitLineIndex);
                linesToSkip = Math.max(0, linesToSkip);

                for (int i = linesToSkip; i < linesInSnippet; i++) {
                    result.append("\n").append(currentLines[i]);
                }
            } else {
                if (lastEndLine != -1) {
                    result.append("\n...\n");
                }
                result.append(currentSnippet);
            }

            // Update lastEndLine to the maximum line number now covered in result
            lastEndLine = Math.max(lastEndLine, currentEndLine);
        }

        return result.toString();
    }
}
