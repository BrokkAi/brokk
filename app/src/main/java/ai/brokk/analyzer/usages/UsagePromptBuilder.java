package ai.brokk.analyzer.usages;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import java.util.Collection;
import java.util.List;

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
public final class UsagePromptBuilder {

    private UsagePromptBuilder() {}

    /**
     * Build a prompt for a single usage hit.
     */
    public static UsagePrompt buildPrompt(
            UsageHit hit,
            CodeUnit codeUnitTarget,
            Collection<CodeUnit> alternatives,
            IAnalyzer analyzer,
            String shortName,
            int maxTokens) {
        return buildPrompt(List.of(hit), codeUnitTarget, alternatives, analyzer, shortName, maxTokens);
    }

    /**
     * Build a prompt for multiple usage hits sharing the same enclosing CodeUnit.
     *
     * @param hits list of usage occurrences (must all share the same enclosing CodeUnit and file)
     * @param codeUnitTarget the intended target code unit
     * @param alternatives other code units with the same short name that are not the target
     * @param analyzer used to retrieve import statements for the file containing the usage
     * @param shortName the short name being searched (e.g., "A.method2")
     * @param maxTokens rough token budget (approx 4 characters per token); non-positive to disable
     * @return UsagePrompt containing filterDescription, combined candidateText, and promptText
     */
    public static UsagePrompt buildPrompt(
            List<UsageHit> hits,
            CodeUnit codeUnitTarget,
            Collection<CodeUnit> alternatives,
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

        // Filter description for RelevanceClassifier.relevanceScore
        String filterDescription = buildFilterDescription(codeUnitTarget, !alternatives.isEmpty());

        // Metadata headers
        sb.append("Short Name of Search: ").append(shortName).append("\n");
        sb.append("Code Unit Target: ").append(codeUnitTarget).append("\n");

        sb.append("Other Possible Matches:");
        if (alternatives.isEmpty()) {
            sb.append(" (none)\n");
        } else {
            sb.append("\n");
            for (CodeUnit alt : alternatives) {
                sb.append(alt.fqName()).append("\n");
            }
        }

        sb.append("File of Hit: ").append(first.file().getRelPath()).append("\n");

        // Gather imports (best effort)
        List<String> imports;
        try {
            imports = analyzer.importStatementsOf(first.file());
        } catch (Throwable t) {
            imports = List.of(); // fail open
        }

        String extension = first.file().extension();
        sb.append("```").append(extension).append("\n");

        for (String imp : imports) {
            sb.append(imp).append("\n");
        }
        if (!imports.isEmpty()) {
            sb.append("\n");
        }

        sb.append("// snippet of method containing possible usage ")
                .append(first.enclosing().fqName())
                .append("\n");

        // Combine and deduplicate snippets
        String combinedSnippets = combineSnippets(hits);
        sb.append(combinedSnippets).append("\n");

        sb.append("// rest of class\n");
        sb.append("```\n");

        if (sb.length() > maxChars) {
            String marker = "\n... [truncated due to token limit]";
            int markerLength = marker.length();
            int safeLimit = Math.max(512, maxChars - markerLength);

            sb.setLength(safeLimit);

            // Ensure we don't leave a markdown code block open
            String current = sb.toString();
            if (!current.trim().endsWith("```")) {
                sb.append("\n```");
            }
            sb.append(marker);
        }

        return new UsagePrompt(filterDescription, combinedSnippets, sb.toString());
    }

    /**
     * Merges snippets from multiple hits into a single block.
     *
     * <p>This algorithm is best-effort: it attempts to deduplicate overlapping lines by looking for
     * literal matches between the end of the previous snippet and the start of the current one. If
     * snippets don't literally overlap (e.g. due to inconsistent context or whitespace), it may
     * occasionally include duplicate lines.
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
        String lastSnippet = null;
        int lastEndLine = -1;

        for (UsageHit hit : sortedHits) {
            String currentSnippet = hit.snippet();
            int currentLine = hit.line();
            // Snippets have ~3 lines above/below context.
            int currentStartLine = Math.max(0, currentLine - 3);

            if (lastSnippet != null && currentStartLine <= lastEndLine + 1) {
                // Overlap or adjacency: try to merge
                String[] lastLines = lastSnippet.split("\n", -1);
                String[] currentLines = currentSnippet.split("\n", -1);

                // Simple sliding-window overlap detection based on line content.
                int overlapIndex = -1;
                for (int i = 0; i < lastLines.length; i++) {
                    // Check if the current line matches the start of the next snippet.
                    // We trim to handle minor indentation/whitespace differences.
                    if (lastLines[i].trim().equals(currentLines[0].trim())) {
                        boolean match = true;
                        for (int j = 0; j < Math.min(lastLines.length - i, currentLines.length); j++) {
                            if (!lastLines[i + j].trim().equals(currentLines[j].trim())) {
                                match = false;
                                break;
                            }
                        }
                        if (match) {
                            overlapIndex = i;
                            break;
                        }
                    }
                }

                if (overlapIndex != -1) {
                    int remainingStart = lastLines.length - overlapIndex;
                    for (int i = remainingStart; i < currentLines.length; i++) {
                        result.append("\n").append(currentLines[i]);
                    }
                } else {
                    // Fallback if content doesn't literally overlap despite line proximity
                    result.append("\n").append(currentSnippet);
                }
            } else {
                if (lastSnippet != null) {
                    result.append("\n...\n");
                }
                result.append(currentSnippet);
            }

            lastSnippet = currentSnippet;
            lastEndLine = currentLine + 3; // Approximate based on snippet generation logic
        }

        return result.toString();
    }

    private static String buildFilterDescription(CodeUnit targetCodeUnit, boolean hasAlternatives) {
        String base = "Determine if the snippet represents a usage of " + targetCodeUnit;
        if (hasAlternatives) {
            base +=
                    ". Consider the list of alternative code units and score how likely the usage matches ONLY the target (not any alternative)";
        }

        return base + ". Return a real number in [0.0, 1.0].";
    }
}
