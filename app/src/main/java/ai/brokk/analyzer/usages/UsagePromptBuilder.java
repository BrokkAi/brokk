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
     *
     * @param hit single usage occurrence (snippet should contain ~3 lines above/below already if desired)
     * @param codeUnitTarget the intended target code unit
     * @param alternatives other code units with the same short name that are not the target
     * @param analyzer used to retrieve import statements for the file containing the usage
     * @param shortName the short name being searched (e.g., "A.method2")
     * @param maxTokens rough token budget (approx 4 characters per token); non-positive to disable
     * @return UsagePrompt containing filterDescription, candidateText, and promptText
     */
    public static UsagePrompt buildPrompt(
            UsageHit hit,
            CodeUnit codeUnitTarget,
            Collection<CodeUnit> alternatives,
            IAnalyzer analyzer,
            String shortName,
            int maxTokens) {

        // Approximate token-to-character budget (very conservative)
        final int maxChars = (maxTokens <= 0) ? Integer.MAX_VALUE : Math.max(512, maxTokens * 4);
        var sb = new StringBuilder(Math.min(maxChars, 32_000));

        // Filter description for RelevanceClassifier.relevanceScore
        String filterDescription = buildFilterDescription(codeUnitTarget, !alternatives.isEmpty());

        // Candidate text is the raw snippet for this single usage
        String candidateText = hit.snippet();

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

        sb.append("File of Hit: ").append(hit.file().getRelPath()).append("\n");

        // Gather imports (best effort)
        List<String> imports;
        try {
            imports = analyzer.importStatementsOf(hit.file());
        } catch (Throwable t) {
            imports = List.of(); // fail open
        }

        String extension = hit.file().extension();
        sb.append("```").append(extension).append("\n");

        for (String imp : imports) {
            sb.append(imp).append("\n");
        }
        if (!imports.isEmpty()) {
            sb.append("\n");
        }

        sb.append("// snippet of method containing possible usage ")
                .append(hit.enclosing().fqName())
                .append("\n");
        sb.append(candidateText).append("\n");
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

        return new UsagePrompt(filterDescription, candidateText, sb.toString());
    }

    private static String buildFilterDescription(CodeUnit targetCodeUnit, boolean hasAlternatives) {
        String base = "Determine if the snippet represents a usage of " + targetCodeUnit;
        if (hasAlternatives) {
            base +=
                    ". Consider the <candidates> list of alternative code units and score how likely the usage matches ONLY the target (not any alternative)";
        }

        if (UsageConfig.isBooleanUsageMode()) {
            return base + ".";
        } else {
            return base + ". Return a real number in [0.0, 1.0].";
        }
    }
}
