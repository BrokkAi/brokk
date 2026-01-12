package ai.brokk.analyzer.usages;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
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
     * @param analyzer used to retrieve import statements for the file containing the usage
     * @param shortName the short name being searched (e.g., "A.method2")
     * @param maxTokens rough token budget (approx 4 characters per token); non-positive to disable
     * @return UsagePrompt containing filterDescription, candidateText, and promptText
     */
    public static UsagePrompt buildPrompt(
            UsageHit hit, CodeUnit codeUnitTarget, IAnalyzer analyzer, String shortName, int maxTokens) {

        // Approximate token-to-character budget (very conservative)
        final int maxChars = (maxTokens <= 0) ? Integer.MAX_VALUE : Math.max(512, maxTokens * 4);
        var sb = new StringBuilder(Math.min(maxChars, 32_000));

        // Filter description for RelevanceClassifier.relevanceScore
        String filterDescription = buildFilterDescription(codeUnitTarget);

        // Candidate text is the raw snippet for this single usage
        String candidateText = hit.snippet();

        // Metadata headers
        sb.append("Short Name: ").append(shortName).append("\n");
        sb.append("Code Unit: ").append(codeUnitTarget.toString()).append("\n");

        sb.append("File: ")
                .append(hit.file().absPath().toString())
                .append("\n");

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

        sb.append("// snippet of method ")
                .append(hit.enclosing().fqName())
                .append("\n");
        sb.append(candidateText).append("\n");
        sb.append("// rest of class\n");
        sb.append("```\n");

        if (sb.length() > maxChars) {
            sb.setLength(maxChars);
            sb.append("\n... [truncated due to token limit]");
        }

        return new UsagePrompt(filterDescription, candidateText, sb.toString());
    }

    private static String buildFilterDescription(CodeUnit targetCodeUnit) {
        if (UsageConfig.isBooleanUsageMode()) {
            return ("Determine if the snippet represents a usage of " + targetCodeUnit + ".");
        } else {
            return ("Determine if the snippet represents a usage of " + targetCodeUnit
                    + ". Score how likely the usage matches the target. Return a real number in [0.0, 1.0].");
        }
    }
}
