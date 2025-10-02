package io.github.jbellis.brokk.analyzer.usages;

import static io.github.jbellis.brokk.util.HtmlUtil.escapeXml;

import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.TreeSitterAnalyzer;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a single-usage prompt record for LLM-based relevance scoring.
 *
 * <p>The builder emits:
 *
 * <ul>
 *   <li>filterDescription: concise text describing the intended target (e.g., the short name and optional candidates)
 *   <li>candidateText: the snippet representing this single usage
 *   <li>promptText: an XML-like block including file path, imports, and a single &lt;usage&gt; block (no IDs)
 * </ul>
 *
 * All textual XML content is escaped, and a conservative token-to-character budget is enforced.
 */
public final class UsagePromptBuilder {

    private UsagePromptBuilder() {}

    /**
     * Build a prompt for a single usage hit.
     *
     * @param hit single usage occurrence (snippet should contain ~3 lines above/below already if desired)
     * @param candidateTargets optional list of candidate targets to include in a comment header
     * @param analyzer used to retrieve import statements for the file containing the usage
     * @param shortName the short name being searched (e.g., "A.method2")
     * @param maxTokens rough token budget (approx 4 characters per token); non-positive to disable
     * @return UsagePrompt containing filterDescription, candidateText, and promptText (no IDs)
     */
    public static UsagePrompt buildPrompt(
            UsageHit hit,
            List<CodeUnit> candidateTargets,
            TreeSitterAnalyzer analyzer,
            String shortName,
            int maxTokens) {

        // Approximate token-to-character budget (very conservative)
        final int maxChars = (maxTokens <= 0) ? Integer.MAX_VALUE : Math.max(512, maxTokens * 4);
        var sb = new StringBuilder(Math.min(maxChars, 32_000));

        // Filter description for RelevanceClassifier.relevanceScore
        String filterDescription = buildFilterDescription(shortName, candidateTargets);

        // Candidate text is the raw snippet for this single usage (unescaped)
        String candidateText = hit.snippet() == null ? "" : hit.snippet();

        // Header comments
        sb.append("<!-- shortName: ").append(escapeXml(shortName)).append(" -->\n");
        if (candidateTargets != null && !candidateTargets.isEmpty()) {
            sb.append("<!-- candidates: ");
            var names = new ArrayList<String>(candidateTargets.size());
            for (var cu : candidateTargets) {
                names.add(escapeXml(cu.fqName()));
            }
            sb.append(String.join(", ", names));
            sb.append(" -->\n");
        }

        // Gather imports (best effort)
        List<String> imports;
        try {
            imports = analyzer.importStatementsOf(hit.file());
        } catch (Throwable t) {
            imports = List.of(); // fail open
        }

        // Start file block
        sb.append("<file path=\"")
                .append(escapeXml(hit.file().absPath().toString()))
                .append("\">\n");

        // Imports block
        sb.append("<imports>\n");
        for (String imp : imports) {
            sb.append(escapeXml(imp)).append("\n");
        }
        sb.append("</imports>\n\n");

        // Single usage block, no id attribute
        int beforeUsageLen = sb.length();
        sb.append("<usage>\n");
        sb.append(escapeXml(candidateText)).append("\n");
        sb.append("</usage>\n");
        if (sb.length() > maxChars) {
            sb.setLength(beforeUsageLen);
            sb.append("<!-- truncated due to token limit -->\n");
            sb.append("</file>\n");
            return new UsagePrompt(filterDescription, candidateText, sb.toString());
        }

        sb.append("</file>\n");
        if (sb.length() > maxChars) {
            sb.append("<!-- truncated due to token limit -->\n");
        }

        return new UsagePrompt(filterDescription, candidateText, sb.toString());
    }

    private static String buildFilterDescription(String shortName, List<CodeUnit> candidateTargets) {
        var sb = new StringBuilder(256);
        sb.append("Determine if the snippet represents a usage of ")
                .append(shortName)
                .append(".");
        if (candidateTargets != null && !candidateTargets.isEmpty()) {
            sb.append(" Candidates: ");
            var names = new ArrayList<String>(candidateTargets.size());
            for (var cu : candidateTargets) {
                names.add(cu.fqName());
            }
            sb.append(String.join(", ", names));
            sb.append(".");
        }
        return sb.toString();
    }
}
